package dev.halone.plugin.android

import android.content.Context

/** The phone apps the wearer added to Halone's home launcher. */
class HomeApps(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var ids: Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
        private set

    operator fun contains(id: String) = id in ids

    fun set(id: String, onHome: Boolean) {
        ids = if (onHome) ids + id else ids - id
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "ids"
    }
}
