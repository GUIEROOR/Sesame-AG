@file:Suppress("UNUSED_PARAMETER")
package android.system

// JVMs have no portable Android directory-fsync API. File fd.sync and atomic moves remain real.
object Os {
    fun open(path: String, flags: Int, mode: Int) = java.io.FileDescriptor()
    fun fsync(descriptor: java.io.FileDescriptor) = Unit
    fun close(descriptor: java.io.FileDescriptor) = Unit
}
object OsConstants { const val O_RDONLY = 0 }
