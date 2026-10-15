package regression

import io.github.aoguai.sesameag.hook.*
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import java.io.File
import java.nio.file.Files as NioFiles
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private fun config(uid: String, self: String = """{"userId":"$uid"}""") {
    File(Files.CONFIG_DIR, uid).mkdirs()
    File(Files.CONFIG_DIR, "$uid/config_v2.json").writeText("{}")
    File(Files.CONFIG_DIR, "$uid/self.json").writeText(self)
}

private fun store(record: AccountSlotRecord) =
    File(Files.CONFIG_DIR, "account-slots.json").writeText(JsonUtil.formatJson(record))

private fun expectActive(vararg ids: String) {
    val snapshot = AccountSlotRegistry.snapshot()
    check(snapshot.errorCode == null) { "Registry unavailable: $snapshot" }
    check(snapshot.activeUserIds == ids.toList()) { "Expected ${ids.toList()}, got $snapshot" }
}

private fun child(action: String) {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        "regression.AccountSlotRegressionKt", Files.CONFIG_DIR.absolutePath, action)
        .inheritIO().start()
    check(process.waitFor() == 0) { "Child process failed: $action" }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        Files.CONFIG_DIR = File(args[0])
        when (args[1]) {
            "read" -> repeat(100) { expectActive("A", "B") }
            "rewrite-self" -> repeat(100) {
                File(Files.CONFIG_DIR, "B/self.json").writeText("")
                Thread.yield()
                config("B")
            }
        }
        return
    }
    val failures = mutableListOf<String>()
    var passed = 0
    fun scenario(name: String, test: () -> Unit) {
        val root = NioFiles.createTempDirectory("account-slots-").toFile()
        Files.CONFIG_DIR = root
        Files.listingUnavailable = false
        PersistentScheduleRegistry.cancelled.clear()
        UserDataStoreManager.released.clear()
        try {
            test()
            passed++
            println("PASS $name")
        } catch (error: Throwable) {
            failures.add("$name: ${error.message}")
            println("FAIL ${failures.last()}")
        } finally {
            root.deleteRecursively()
        }
    }
    fun twoAccounts() {
        config("A"); config("B")
        expectActive("A", "B")
    }

    scenario("empty self during overwrite preserves both slots on disk") {
        twoAccounts()
        File(Files.CONFIG_DIR, "B/self.json").writeText("")
        expectActive("A", "B")
        config("B")
        child("read")
    }
    scenario("invalid or mismatched self does not revoke persisted selection") {
        twoAccounts()
        for (body in listOf("{", """{"userId":"other"}""")) {
            config("B", body)
            expectActive("A", "B")
            check(AccountSlotRegistry.snapshot().orphanedActiveUserIds == listOf("B"))
        }
        config("B")
        expectActive("A", "B")
    }
    scenario("temporary directory listing failure preserves record bytes") {
        twoAccounts()
        val file = File(Files.CONFIG_DIR, "account-slots.json")
        val before = file.readText()
        Files.listingUnavailable = true
        repeat(20) { expectActive("A", "B") }
        check(file.readText() == before)
        Files.listingUnavailable = false
        expectActive("A", "B")
    }
    scenario("missing config keeps slot and can be explicitly removed") {
        twoAccounts()
        File(Files.CONFIG_DIR, "B/config_v2.json").delete()
        expectActive("A", "B")
        check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
        expectActive("A")
        check(PersistentScheduleRegistry.cancelled == listOf("B"))
        check(UserDataStoreManager.released == listOf("B"))
    }
    scenario("explicitly removed configured account remains manual-only") {
        twoAccounts()
        check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
        repeat(10) {
            check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Denied)
            expectActive("A")
        }
        check(AccountSlotRegistry.addExecutableSlot(null, "B").replaced)
        expectActive("A", "B")
    }
    scenario("first and second runtime UID provision, third stays denied") {
        for (uid in listOf("A", "B")) {
            val admission = AccountSlotRegistry.admitRuntimeUser(uid)
            check(admission is AccountSlotAdmission.Allowed && admission.requiresConfirmation)
            config(uid)
            check(AccountSlotRegistry.confirmRuntimeUser(uid) == AccountSlotRuntimeConfirmation.Confirmed)
        }
        expectActive("A", "B")
        check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("account_slot_full"))
        repeat(100) {
            check(AccountSlotRegistry.admitRuntimeUser(if (it % 2 == 0) "A" else "B") is AccountSlotAdmission.Allowed)
            expectActive("A", "B")
        }
    }
    scenario("expired completed reservation recovers after process restart") {
        config("A"); config("B")
        store(AccountSlotRecord(activeUserIds = listOf("A"), pendingRuntimeUserId = "B",
            pendingRuntimeSinceMs = System.currentTimeMillis() - 180_000))
        child("read")
        expectActive("A", "B")
        check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
    }
    scenario("late confirmation succeeds when files are verified") {
        config("A"); config("B")
        store(AccountSlotRecord(activeUserIds = listOf("A"), pendingRuntimeUserId = "B",
            pendingRuntimeSinceMs = System.currentTimeMillis() - 180_000))
        check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
        expectActive("A", "B")
    }
    scenario("expired incomplete reservation clears without evicting active UID") {
        config("A")
        store(AccountSlotRecord(activeUserIds = listOf("A"), pendingRuntimeUserId = "B",
            pendingRuntimeSinceMs = System.currentTimeMillis() - 180_000))
        expectActive("A")
        check(AccountSlotRegistry.snapshot().pendingRuntimeUserId == null)
        check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Allowed)
        check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("account_slot_provision_pending"))
    }
    scenario("invalid IDs and over-cap records fail closed without overwriting") {
        check(AccountSlotRegistry.admitRuntimeUser("../B") == AccountSlotAdmission.Denied("invalid_user_id"))
        val file = File(Files.CONFIG_DIR, "account-slots.json")
        for (body in listOf("{", JsonUtil.formatJson(AccountSlotRecord(activeUserIds = listOf("A", "B", "C"))))) {
            file.writeText(body)
            check(AccountSlotRegistry.snapshot().errorCode == "registry_unavailable")
            check(file.readText() == body)
        }
    }
    scenario("over-two migration requires selection") {
        config("A"); config("B"); config("C")
        check(AccountSlotRegistry.snapshot().migrationState == AccountSlotMigrationState.SELECTION_REQUIRED)
        check(AccountSlotRegistry.addExecutableSlot(null, "A").replaced)
        check(AccountSlotRegistry.addExecutableSlot(null, "B").replaced)
        check(!AccountSlotRegistry.addExecutableSlot(null, "C").replaced)
        expectActive("A", "B")
    }
    scenario("concurrent JVM reads and cross-process self overwrites keep both slots") {
        twoAccounts()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..2).map { Callable { repeat(100) { expectActive("A", "B") } } } +
                Callable { child("rewrite-self") }
            pool.invokeAll(jobs).forEach { it.get() }
        } finally { pool.shutdownNow() }
        child("read")
    }
    println("Account slot regression: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString("\n") }
}
