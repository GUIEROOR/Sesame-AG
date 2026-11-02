package android.content

import android.net.Uri
import java.io.File

class Context(val directory: File) {
    val packageName = "io.github.aoguai.sesameag"
    val applicationContext get() = this
    val noBackupFilesDir = File(directory, "no-backup").apply { mkdirs() }
    val cacheDir = File(directory, "cache").apply { mkdirs() }
    val filesDir = File(directory, "files").apply { mkdirs() }
    val contentResolver = ContentResolver()
}

class ContentResolver {
    fun openInputStream(uri: Uri) = uri.file.inputStream()
    fun openOutputStream(uri: Uri, mode: String) = uri.file.outputStream()
}
