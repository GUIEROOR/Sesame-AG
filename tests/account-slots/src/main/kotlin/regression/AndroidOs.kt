package android.os

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

interface IBinder {
    interface DeathRecipient { fun binderDied() }
    fun linkToDeath(recipient: DeathRecipient, flags: Int)
    fun unlinkToDeath(recipient: DeathRecipient, flags: Int): Boolean
}

open class Binder : IBinder {
    private val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { recipients.add(recipient) }
    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = recipients.remove(recipient)
    fun die() { recipients.toList().forEach { it.binderDied() } }
    companion object {
        val callingUid = ThreadLocal.withInitial { 1000 }
        @JvmStatic fun getCallingUid() = callingUid.get()
    }
}

class Bundle {
    private val values = mutableMapOf<String, Any>()
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun putLong(key: String, value: Long) { values[key] = value }
    fun getBoolean(key: String) = values[key] as? Boolean ?: false
    fun getLong(key: String) = values[key] as? Long ?: 0L
}

class RemoteCallbackList<T> {
    private val targets = CopyOnWriteArrayList<T>()
    fun register(target: T) = targets.addIfAbsent(target)
    fun unregister(target: T) = targets.remove(target)
    fun beginBroadcast() = targets.size
    fun getBroadcastItem(index: Int) = targets[index]
    fun finishBroadcast() = Unit
}

class ParcelFileDescriptor(
    val input: InputStream? = null,
    val output: OutputStream? = null,
    private val error: java.util.concurrent.atomic.AtomicReference<String?> = java.util.concurrent.atomic.AtomicReference(),
) : AutoCloseable {
    override fun close() { input?.close(); output?.close() }
    fun closeWithError(message: String) { error.set(message); close() }
    fun checkError() { error.get()?.let { throw java.io.IOException(it) } }
    class AutoCloseInputStream(private val descriptor: ParcelFileDescriptor) : java.io.FilterInputStream(descriptor.input) {
        override fun close() = descriptor.close()
    }
    class AutoCloseOutputStream(private val descriptor: ParcelFileDescriptor) : java.io.FilterOutputStream(descriptor.output) {
        override fun close() = descriptor.close()
    }
    companion object {
        const val MODE_READ_ONLY = 1
        fun open(file: File, mode: Int) = ParcelFileDescriptor(input = file.inputStream())
        fun createReliablePipe(): Array<ParcelFileDescriptor> {
            val input = PipedInputStream(65536)
            val output = PipedOutputStream(input)
            val error = java.util.concurrent.atomic.AtomicReference<String?>()
            return arrayOf(ParcelFileDescriptor(input = input, error = error), ParcelFileDescriptor(output = output, error = error))
        }
    }
}
