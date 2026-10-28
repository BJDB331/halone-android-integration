package dev.halone.plugin.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import android.view.Display

/**
 * Lets Halone's pad switch the phone's own panel off, and back on, while
 * the glasses stay on: really off, through the Shizuku service, where
 * Halone alone can only dim its window.
 *
 * Halone calls `call(method, null, extras)`: `off` with `token` (a binder
 * of its own) and `display` (the glasses' display id), then `renew` every
 * few seconds and `on` when done, each answering `ok`. The panel is held
 * off on a lease ([dev.halone.plugin.android.privileged.PhoneScreen]): it
 * comes back if the renewals stop, if Halone's process dies, if the glasses'
 * display goes, or if the service does.
 *
 * Only Halone may call it.
 */
class PhoneScreenProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = context!!.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (HALONE !in caller) throw SecurityException("only Halone may switch the phone screen")
        val command = when (method) {
            "off" -> OFF
            "renew" -> RENEW
            "on" -> ON
            else -> throw IllegalArgumentException("unknown phone screen operation")
        }
        val token = extras?.getBinder("token") ?: throw IllegalArgumentException("missing token")
        val display = extras.getInt("display", Display.INVALID_DISPLAY)
        // Shizuku set up and Halone running this plugin, or nothing to do it with.
        val service = AndroidAppsProvider.running?.privileged?.service
        val ok = service != null && runCatching { service.phoneScreen(command, token, display) }
            .onFailure { Log.w(TAG, "phone screen $method", it) }
            .isSuccess
        return Bundle().apply { putBoolean("ok", ok) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val TAG = "HaloneAndroidApps"
        const val HALONE = "dev.halone.app"
        // As IDisplayService.phoneScreen takes them.
        const val OFF = 1
        const val RENEW = 2
        const val ON = 3
    }
}
