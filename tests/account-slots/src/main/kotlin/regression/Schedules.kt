package io.github.aoguai.sesameag.hook.keepalive

import android.content.Context

object PersistentScheduleRegistry {
    val cancelled = mutableListOf<String>()
    fun cancelByOwner(context: Context?, userId: String) { cancelled.add(userId) }
}
