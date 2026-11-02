package regression

import android.content.Context
import android.net.Uri
import android.os.Binder
import io.github.aoguai.sesameag.hook.*
import io.github.aoguai.sesameag.hook.keepalive.PersistentScheduleRegistry
import io.github.aoguai.sesameag.storage.PrivateStorage
import io.github.aoguai.sesameag.storage.StorageBackup
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.storage.StoreFile
import io.github.aoguai.sesameag.util.CommandUtil
import io.github.aoguai.sesameag.util.Files
import io.github.aoguai.sesameag.util.JsonUtil
import io.github.aoguai.sesameag.util.UserDataStoreManager
import java.io.File
import java.io.IOException
import java.nio.file.Files as NioFiles
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private lateinit var context: Context
private fun stored(path: String): File = path.split('/').fold(Files.CONFIG_DIR, Files::child)
private fun write(path: String, body: String) = Files.writeAtomically(body, stored(path))
private fun read(path: String) = Files.readFromFile(stored(path))
private fun ownerFile(path: String) = File(PrivateStorage.root, "config/$path")
private fun config(uid: String, self: String = """{"userId":"$uid"}""") {
    stored(uid).mkdirs()
    write("$uid/config_v2.json", "{}")
    write("$uid/self.json", self)
}
private fun store(record: AccountSlotRecord) = write("account-slots.json", JsonUtil.formatJson(record))
private fun pending() = AccountSlotRecord(activeUserIds = listOf("A"), pendingRuntimeUserId = "B",
    pendingRuntimeSinceMs = System.currentTimeMillis() - 180_000)
private fun expectActive(vararg ids: String) {
    val snapshot = AccountSlotRegistry.snapshot()
    check(snapshot.errorCode == null) { "Registry unavailable: $snapshot" }
    check(snapshot.activeUserIds == ids.toList()) { "Expected ${ids.toList()}, got $snapshot" }
}
private fun twoAccounts() { config("A"); config("B"); expectActive("A", "B") }
private fun prepare(directory: File) {
    context = Context(directory)
    File(context.noBackupFilesDir, "store/config").mkdirs()
    File(context.noBackupFilesDir, "store/.store-ready").writeText("ready")
    PrivateStorage.initialize(context)
}
private fun restartModule() {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
        "regression.AccountSlotRegressionKt", context.directory.absolutePath).inheritIO().start()
    if (!child.waitFor(30, TimeUnit.SECONDS)) { child.destroyForcibly(); error("Restart timed out") }
    check(child.exitValue() == 0) { "Module restart failed" }
}

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        context = Context(File(args[0]))
        PrivateStorage.initialize(context)
        repeat(100) { expectActive("A", "B") }
        return
    }
    val failures = mutableListOf<String>()
    var passed = 0
    for (mode in listOf("module-owner", "host-session")) {
        if (mode == "host-session") StoreAccess.useRemote()
        fun scenario(name: String, test: () -> Unit) {
            val root = NioFiles.createTempDirectory("account-private-store-").toFile()
            Files.listingUnavailable = false
            Files.unreadableSelfUserId = null
            Files.cancelSelfUserId = null
            Files.cancelRegistryRead = false
            CommandUtil.disconnected = false
            Binder.callingUid.set(1000)
            PersistentScheduleRegistry.cancelled.clear()
            UserDataStoreManager.released.clear()
            try {
                prepare(root)
                test()
                passed++
                println("PASS [$mode] $name")
            } catch (error: Throwable) {
                failures.add("[$mode] $name: ${error.message}")
                println("FAIL ${failures.last()}")
            } finally { root.deleteRecursively() }
        }

        scenario("empty self preserves both confirmed slots after restart") {
            twoAccounts(); write("B/self.json", ""); expectActive("A", "B")
            config("B"); restartModule()
        }
        scenario("invalid or mismatched self does not revoke selection") {
            twoAccounts()
            for (body in listOf("{", """{"userId":"other"}""")) {
                config("B", body); expectActive("A", "B")
                check(AccountSlotRegistry.snapshot().orphanedActiveUserIds == listOf("B"))
            }
            config("B"); expectActive("A", "B")
        }
        scenario("directory read failure fails closed without rewriting selection") {
            twoAccounts(); val before = read("account-slots.json")
            Files.listingUnavailable = true
            check(AccountSlotRegistry.snapshot().errorCode == "registry_unavailable")
            check(AccountSlotRegistry.checkExecutableUser("A") is AccountSlotExecutionCheck.Allowed)
            check(read("account-slots.json") == before)
            Files.listingUnavailable = false; expectActive("A", "B")
        }
        scenario("missing config keeps slot and permits explicit removal") {
            twoAccounts(); stored("B/config_v2.json").delete(); expectActive("A", "B")
            check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
            expectActive("A")
            check(PersistentScheduleRegistry.cancelled == listOf("B"))
            check(UserDataStoreManager.released == listOf("B"))
        }
        scenario("manual removal remains manual-only") {
            twoAccounts(); check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
            repeat(10) { check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Denied); expectActive("A") }
            check(AccountSlotRegistry.addExecutableSlot(null, "B").replaced); expectActive("A", "B")
        }
        scenario("two new runtime UIDs provision and 1000 UID switches preserve slots") {
            for (uid in listOf("A", "B")) {
                val admission = AccountSlotRegistry.admitRuntimeUser(uid)
                check(admission is AccountSlotAdmission.Allowed && admission.requiresConfirmation)
                config(uid)
                check(AccountSlotRegistry.confirmRuntimeUser(uid) == AccountSlotRuntimeConfirmation.Confirmed)
            }
            expectActive("A", "B")
            check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("account_slot_not_selected"))
            repeat(1000) {
                check(AccountSlotRegistry.admitRuntimeUser(if (it % 2 == 0) "A" else "B") is AccountSlotAdmission.Allowed)
                check(AccountSlotRegistry.checkExecutableUser(if (it % 2 == 0) "A" else "B") is AccountSlotExecutionCheck.Allowed)
            }
            expectActive("A", "B")
        }
        scenario("expired completed reservation recovers after module restart") {
            config("A"); config("B"); store(pending()); restartModule(); expectActive("A", "B")
            check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
        }
        scenario("late verified confirmation succeeds") {
            config("A"); config("B"); store(pending())
            check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
            expectActive("A", "B")
        }
        scenario("expired incomplete reservation survives reads and resumes") {
            config("A"); store(pending()); expectActive("A")
            check(AccountSlotRegistry.snapshot().pendingRuntimeUserId == "B")
            check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Allowed)
            config("B"); check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
            expectActive("A", "B")
        }
        scenario("partial self after timeout never discards enrollment") {
            config("A"); config("B", ""); store(pending())
            repeat(20) { expectActive("A"); check(AccountSlotRegistry.snapshot().pendingRuntimeUserId == "B") }
            check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Expired)
            check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Allowed)
            config("B"); check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
            expectActive("A", "B")
        }
        scenario("self read I/O error preserves the entire pending record") {
            config("A"); config("B"); store(pending()); val before = read("account-slots.json")
            Files.unreadableSelfUserId = "B"
            check(AccountSlotRegistry.snapshot().errorCode == "registry_unavailable")
            check(read("account-slots.json") == before)
            Files.unreadableSelfUserId = null; expectActive("A", "B")
        }
        scenario("missing or malformed self cannot re-enable a disabled known UID") {
            twoAccounts(); check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
            for (body in listOf("", "{", """{"userId":"other"}""")) {
                config("B", body); check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Denied)
            }
            stored("B/self.json").delete(); write("B/config_v2.json", "")
            check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Denied)
            expectActive("A")
        }
        scenario("competing runtime UID reclaims only an expired reservation") {
            config("A"); expectActive("A")
            check(AccountSlotRegistry.admitRuntimeUser("B") is AccountSlotAdmission.Allowed)
            check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("account_slot_provision_pending"))
            store(pending()); check(AccountSlotRegistry.admitRuntimeUser("C") is AccountSlotAdmission.Allowed)
            config("B"); check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.PendingPersistence)
            config("C"); check(AccountSlotRegistry.confirmRuntimeUser("C") == AccountSlotRuntimeConfirmation.Confirmed)
            expectActive("A", "C")
        }
        scenario("manual selection reclaims expired but not fresh enrollment") {
            config("A"); config("C")
            store(pending().copy(pendingRuntimeSinceMs = System.currentTimeMillis()))
            check(!AccountSlotRegistry.addExecutableSlot(null, "C").replaced)
            store(pending()); check(AccountSlotRegistry.addExecutableSlot(null, "C").replaced)
            expectActive("A", "C")
            config("B"); check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.PendingPersistence)
        }
        scenario("confirmed slot can be removed while another UID enrolls") {
            config("A"); store(pending().copy(pendingRuntimeSinceMs = System.currentTimeMillis()))
            check(AccountSlotRegistry.removeExecutableSlot(null, "A").replaced); expectActive()
            check(AccountSlotRegistry.snapshot().pendingRuntimeUserId == "B")
            config("B"); check(AccountSlotRegistry.confirmRuntimeUser("B") == AccountSlotRuntimeConfirmation.Confirmed)
            expectActive("B"); check(AccountSlotRegistry.admitRuntimeUser("A") is AccountSlotAdmission.Denied)
        }
        scenario("concurrent clients competing for last slot cannot exceed two") {
            config("A"); expectActive("A")
            val pool = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2); val start = CountDownLatch(1)
            try {
                val results = listOf("B", "C").map { uid -> pool.submit(Callable {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    when (val admission = AccountSlotRegistry.admitRuntimeUser(uid)) {
                        is AccountSlotAdmission.Allowed -> {
                            config(uid)
                            check(AccountSlotRegistry.confirmRuntimeUser(uid) == AccountSlotRuntimeConfirmation.Confirmed)
                        }
                        is AccountSlotAdmission.Denied -> check(admission.reasonCode in
                            listOf("account_slot_not_selected", "account_slot_provision_pending"))
                    }
                }) }
                check(ready.await(10, TimeUnit.SECONDS)); start.countDown()
                results.forEach { it.get(20, TimeUnit.SECONDS) }
            } finally { start.countDown(); pool.shutdownNow() }
            val snapshot = AccountSlotRegistry.snapshot()
            check(snapshot.errorCode == null && snapshot.activeUserIds.size == 2)
            check(snapshot.activeUserIds.first() == "A" && snapshot.activeUserIds.distinct().size == 2)
            check(snapshot.pendingRuntimeUserId == null)
        }
        scenario("corrupt registry fails closed and preserves saved bytes") {
            twoAccounts(); val saved = read("account-slots.json"); write("account-slots.json", "{")
            check(AccountSlotRegistry.checkExecutableUser("A") == AccountSlotExecutionCheck.RegistryUnavailable)
            check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("registry_unavailable"))
            check(read("account-slots.json") == "{")
            write("account-slots.json", saved); expectActive("A", "B")
        }
        scenario("invalid UID and over-cap record never overwrite storage") {
            check(AccountSlotRegistry.admitRuntimeUser("../B") == AccountSlotAdmission.Denied("invalid_user_id"))
            for (body in listOf("{", JsonUtil.formatJson(AccountSlotRecord(activeUserIds = listOf("A", "B", "C"))))) {
                write("account-slots.json", body)
                check(AccountSlotRegistry.snapshot().errorCode == "registry_unavailable")
                check(read("account-slots.json") == body)
            }
        }
        scenario("over-two legacy candidates require explicit selection") {
            config("A"); config("B"); config("C")
            check(AccountSlotRegistry.snapshot().migrationState == AccountSlotMigrationState.SELECTION_REQUIRED)
            check(AccountSlotRegistry.addExecutableSlot(null, "A").replaced)
            check(AccountSlotRegistry.addExecutableSlot(null, "B").replaced)
            check(!AccountSlotRegistry.addExecutableSlot(null, "C").replaced); expectActive("A", "B")
        }
        scenario("concurrent private self updates and readers retain both slots") {
            twoAccounts(); val pool = Executors.newFixedThreadPool(4)
            try {
                val jobs = (0..2).map { Callable { repeat(100) { expectActive("A", "B") } } } + Callable {
                    repeat(100) { write("B/self.json", ""); config("B") }
                }
                pool.invokeAll(jobs).forEach { it.get(20, TimeUnit.SECONDS) }
            } finally { pool.shutdownNow() }
            expectActive("A", "B"); restartModule()
        }

        scenario("private resources are handles rather than normal filesystem paths") {
            config("A")
            check(stored("A/config_v2.json") is StoreFile && stored("A/config_v2.json").exists())
            check(!File(Files.CONFIG_DIR, "A/config_v2.json").exists())
            check(runCatching { Files.child(Files.CONFIG_DIR, "A/config_v2.json") }.isFailure)
        }
        scenario("unready migration does not initialize an empty registry") {
            twoAccounts(); val before = ownerFile("account-slots.json").readText()
            File(PrivateStorage.root, ".store-ready").delete(); PrivateStorage.initialize(context)
            check(AccountSlotRegistry.snapshot().errorCode == "registry_unavailable")
            check(ownerFile("account-slots.json").readText() == before)
            File(PrivateStorage.root, ".store-ready").writeText("ready"); PrivateStorage.initialize(context)
            expectActive("A", "B")
        }
        scenario("temporary storage unavailability never clears account slots") {
            twoAccounts(); val before = ownerFile("account-slots.json").readText()
            if (StoreAccess.isRemote) CommandUtil.disconnected = true
            else PrivateStorage.update(PrivateStorage.State(ready = false))
            check(AccountSlotRegistry.admitRuntimeUser("C") == AccountSlotAdmission.Denied("registry_unavailable"))
            check(ownerFile("account-slots.json").readText() == before)
            CommandUtil.disconnected = false; PrivateStorage.initialize(context); expectActive("A", "B")
        }
        scenario("full backup round trip preserves slots and UID-isolated state") {
            twoAccounts(); write("A/status.json", """{"owner":"A","count":1}""")
            write("B/status.json", """{"owner":"B","count":2}""")
            val backup = Uri(File(context.cacheDir, "backup.zip"))
            StorageBackup.exportDirectory(context, backup, PrivateStorage.root)
            write("A/status.json", """{"owner":"A","count":99}""")
            check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
            runBlocking { StorageBackup.restore(context, backup) }
            expectActive("A", "B")
            check(read("A/status.json") == """{"owner":"A","count":1}""")
            check(read("B/status.json") == """{"owner":"B","count":2}""")
            restartModule()
        }
        scenario("legacy snapshot and interrupted account journal retain pending UID") {
            val staging = File(context.noBackupFilesDir, "legacy-snapshot")
            for (uid in listOf("A", "B")) {
                File(staging, "config/$uid").mkdirs()
                File(staging, "config/$uid/config_v2.json").writeText("{}")
                File(staging, "config/$uid/self.json").writeText("""{"userId":"$uid"}""")
            }
            File(staging, "config/account-slots.json").writeText(JsonUtil.formatJson(pending()))
            File(staging, "config/A/status.json").writeText("{}")
            val journal = org.json.JSONObject().put("status.json", """{"restored":true}""")
            File(staging, "config/A/.account-transaction.json").writeText(journal.toString())
            StorageBackup.validateAndRecover(staging); StoreAccess.installSnapshot(staging)
            expectActive("A", "B")
            check(read("A/status.json") == """{"restored":true}""")
            check(!stored("A/.account-transaction.json").exists())
        }
        scenario("storage sessions reject a changed Binder caller UID") {
            twoAccounts(); val client = Binder(); val session = StoreAccess.begin(client, 1000)
            try {
                check(session.stat(1, "", "account-slots.json").getBoolean("exists"))
                Binder.callingUid.set(2000)
                check(runCatching { session.stat(2, "A", "self.json") }.isFailure)
            } finally { Binder.callingUid.set(1000); session.close() }
            expectActive("A", "B")
        }
        scenario("cancelled self and registry reads preserve enrollment and propagate cancellation") {
            config("A"); config("B"); store(pending()); val before = read("account-slots.json")
            Files.cancelSelfUserId = "B"
            val failure = runCatching { AccountSlotRegistry.snapshot() }.exceptionOrNull()
            check(failure is CancellationException)
            check(read("account-slots.json") == before)
            Files.cancelSelfUserId = null; expectActive("A", "B")
            val confirmed = read("account-slots.json")
            Files.cancelRegistryRead = true
            check(runCatching { AccountSlotRegistry.snapshot() }.exceptionOrNull() is CancellationException)
            check(ownerFile("account-slots.json").readText() == confirmed)
            Files.cancelRegistryRead = false; expectActive("A", "B")
        }
        scenario("large private writes and failed atomic replacement preserve other state") {
            twoAccounts(); val body = org.json.JSONObject().put("payload", "a".repeat(262144)).toString()
            write("A/status.json", body); check(read("A/status.json") == body)
            val before = read("account-slots.json")
            check(runCatching {
                StoreAccess.atomicWrite(ownerFile("account-slots.json"), "broken".byteInputStream()) {
                    throw IOException("simulated incomplete input")
                }
            }.isFailure)
            check(read("account-slots.json") == before); expectActive("A", "B")
        }
        scenario("nested transactions and deferred actions do not reacquire held permit") {
            twoAccounts(); var after = false
            StoreAccess.transaction {
                expectActive("A", "B")
                check(AccountSlotRegistry.removeExecutableSlot(null, "B").replaced)
                StoreAccess.afterTransaction { expectActive("A"); after = true }
                check(!after)
            }
            check(after); expectActive("A")
        }
        scenario("interrupted private snapshot installation recovers old selection") {
            twoAccounts()
            val previous = File(context.noBackupFilesDir, "store-restore-previous")
            check(PrivateStorage.root.renameTo(previous))
            PrivateStorage.initialize(context); expectActive("A", "B")
            check(!previous.exists())
        }
        scenario("invalid backup cannot replace live account data") {
            twoAccounts(); val before = read("account-slots.json")
            val backup = File(context.cacheDir, "invalid.zip")
            java.util.zip.ZipOutputStream(backup.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
                zip.write("""{"format":"sesame-storage-backup","files":[{"name":"../escape","length":0,"sha256":""}]}""".toByteArray())
                zip.closeEntry()
            }
            check(runCatching { StorageBackup.read(context, Uri(backup)) }.isFailure)
            check(read("account-slots.json") == before); expectActive("A", "B")
        }
        scenario("cancelled transaction and dead client release storage permit") {
            twoAccounts()
            check(runCatching { StoreAccess.transaction<Unit> { throw CancellationException("cancel") } }.exceptionOrNull() is CancellationException)
            val client = Binder(); val session = StoreAccess.begin(client, 1000)
            client.die()
            check(runCatching { session.stat(1, "", "account-slots.json") }.isFailure)
            expectActive("A", "B")
        }
    }
    println("Account/private-storage regression: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString("\n") }
}
