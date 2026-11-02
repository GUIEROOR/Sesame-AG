@file:Suppress("UNUSED_PARAMETER")
package io.github.aoguai.sesameag.storage.migration

import android.content.Context
import android.net.Uri

// Root/Shizuku transport needs a device; snapshot validation and installation are production code.
object LegacyStorageMigration {
    fun hasCleanup(context: Context) = false
    suspend fun inspect(context: Context) = Unit
    suspend fun run(context: Context) = Unit
    suspend fun backup(context: Context, uri: Uri) = Unit
    suspend fun restore(context: Context, uri: Uri) = Unit
    suspend fun cleanup(context: Context) = Unit
}
