package dev.halone.plugin.android.privileged

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process

/**
 * A context that names the package the system expects for this process's
 * uid. The service runs as shell (or root) but its context is the plugin's,
 * and services such as the display manager refuse a package name that does
 * not belong to the caller.
 */
internal class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName() = SHELL_PACKAGE

    override fun getOpPackageName() = SHELL_PACKAGE

    override fun getAttributionSource(): AttributionSource =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        } else {
            super.getAttributionSource()
        }

    override fun getApplicationContext(): Context = this

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
