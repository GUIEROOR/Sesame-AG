@file:Suppress("UNUSED_PARAMETER")

package io.github.aoguai.sesameag.util

import java.io.File

// Only platform boundaries are replaced; the registry, JSON codec and user DTO are production code.
object Files {
    lateinit var CONFIG_DIR: File
    var listingUnavailable = false
    fun listExistingUserConfigIds(): List<String> =
        if (listingUnavailable) emptyList() else CONFIG_DIR.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "config_v2.json").isFile }.map { it.name }
    fun readFromFile(file: File): String = file.readText()
}

object Log {
    fun record(tag: String, message: String) = Unit
    fun runtime(tag: String, message: String) = Unit
    fun printStackTrace(error: Throwable) = Unit
}

object UserDataStoreManager {
    val released = mutableListOf<String>()
    fun releaseInstance(userId: String) { released.add(userId) }
}
