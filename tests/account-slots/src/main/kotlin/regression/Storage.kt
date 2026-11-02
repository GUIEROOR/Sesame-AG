@file:Suppress("UNUSED_PARAMETER")

package io.github.aoguai.sesameag.util

import java.io.File
import java.io.IOException
import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.github.aoguai.sesameag.IStoreListener
import io.github.aoguai.sesameag.storage.StoreAccess
import io.github.aoguai.sesameag.storage.StoreFile

// Resource handles and all private I/O route through the production StoreAccess.
object Files {
    val CONFIG_DIR: File = StoreFile(StoreFile.root, "config")
    var listingUnavailable = false
    var unreadableSelfUserId: String? = null
    var cancelSelfUserId: String? = null
    var cancelRegistryRead = false
    fun child(parent: File, name: String): File = if (parent is StoreFile) StoreFile(parent, name) else File(parent, name)
    fun listExistingUserConfigIds(requireReadable: Boolean = false): List<String> {
        if (listingUnavailable) {
            if (requireReadable) throw IOException("simulated directory read failure")
            return emptyList()
        }
        return CONFIG_DIR.listFiles().orEmpty()
            .filter { it.isDirectory && child(it, "config_v2.json").isFile }.map { it.name }
    }
    fun readFromFile(file: File): String {
        if (cancelRegistryRead && file.name == "account-slots.json") {
            throw kotlinx.coroutines.CancellationException("simulated cancelled registry read")
        }
        if (file.name == "self.json" && file.parentFile?.name == cancelSelfUserId) {
            throw kotlinx.coroutines.CancellationException("simulated cancelled storage operation")
        }
        if (file.name == "self.json" && file.parentFile?.name == unreadableSelfUserId) {
            throw IOException("simulated transient self read failure")
        }
        return if (file is StoreFile) StoreAccess.read(file) else file.readText()
    }
    fun writeAtomically(text: String, file: File): Boolean {
        if (file is StoreFile) StoreAccess.write(file, text)
        else StoreAccess.atomicWrite(file, text.byteInputStream())
        return true
    }
}

object Log {
    fun record(tag: String, message: String) = Unit
    fun runtime(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = Unit
    fun printStackTrace(error: Throwable) = Unit
}
object Logback { fun <T> withFileLoggingPaused(action: () -> T) = action() }

object UserDataStoreManager {
    val released = mutableListOf<String>()
    fun releaseInstance(userId: String) { released.add(userId) }
}

object CommandUtil {
    var disconnected = false
    private val service = StorageService()
    fun hasStorageConnection() = !disconnected
    fun storageService(): StorageService {
        if (disconnected) throw IOException("simulated disconnected host")
        return service
    }
    fun executeCommand(context: Context, command: String): String {
        check(command == "am force-stop --user 0 com.eg.android.AlipayGphone && printf stopped")
        return "stopped"
    }
    class StorageService {
        fun registerStoreListener(listener: IStoreListener) = StoreAccess.register(listener)
        fun beginStoreTransaction(client: IBinder) = StoreAccess.begin(client, Binder.getCallingUid())
    }
}
