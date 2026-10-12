package dev.halone.plugin.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import dev.halone.plugin.android.privileged.DisplayService
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/** The connection to [DisplayService] through Shizuku or Sui, and what is missing while there is none. */
class Privileged(private val context: Context) {
    enum class State { NO_SHIZUKU, NOT_RUNNING, NO_PERMISSION, DENIED, CONNECTING, READY }

    var state = State.NOT_RUNNING
        private set
    var service: IDisplayService? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var bound = false
    private val displayCallbacks = HashMap<Int, DisplayCallbacks>()

    interface DisplayCallbacks {
        fun onEmptied()
        fun onOrientationRequested(orientation: Int)
    }

    private val displayListener = object : IDisplayListener.Stub() {
        override fun onDisplayEmptied(displayId: Int) {
            main.post { displayCallbacks[displayId]?.onEmptied() }
        }

        override fun onOrientationRequested(displayId: Int, orientation: Int) {
            main.post { displayCallbacks[displayId]?.onOrientationRequested(orientation) }
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, DisplayService::class.java.name))
            .daemon(false)
            .processNameSuffix("display")
            .debuggable(BuildConfig.DEBUG)
            .version(serviceVersion())
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val connected = IDisplayService.Stub.asInterface(binder)
            try {
                connected.setListener(displayListener)
            } catch (e: RemoteException) {
                Log.w(TAG, "setListener", e)
                return
            }
            service = connected
            update(State.READY)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            // Shizuku drops this connection's registration right after this callback, so a bind made inside it is lost.
            main.post { refresh() }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            service = null
            bound = false
            refresh()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { refresh() } }

    fun start() {
        Sui.init(context.packageName)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (bound) runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        bound = false
        service = null
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun watchDisplay(displayId: Int, callbacks: DisplayCallbacks?) {
        if (callbacks == null) displayCallbacks.remove(displayId) else displayCallbacks[displayId] = callbacks
    }

    /** Asks for whatever is missing: Shizuku's permission prompt on the phone, or a new connection. */
    fun requestAccess() {
        refresh()
        if (state == State.NO_PERMISSION || state == State.DENIED) requestPermission()
    }

    fun refresh() {
        if (service?.asBinder()?.pingBinder() == true) return update(State.READY)
        service = null
        val next = missingAccess(context) ?: State.CONNECTING.also { bind() }
        update(next)
    }

    private fun bind() {
        if (bound) return
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            bound = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "bindUserService", e)
        }
    }

    private fun update(next: State) {
        if (next == state) return
        state = next
        listeners.toList().forEach { it() }
    }

    @Suppress("DEPRECATION")
    private fun serviceVersion(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (BuildConfig.DEBUG) (info.lastUpdateTime / 1000).toInt() else info.versionCode
    }

    companion object {
        private const val TAG = "HaloneAndroidApps"
        private const val PERMISSION_REQUEST = 1
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** What stands between this app and Shizuku, or null once it may bind the service. */
        fun missingAccess(context: Context): State? = when {
            !Shizuku.pingBinder() ->
                if (runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess) State.NOT_RUNNING else State.NO_SHIZUKU
            Shizuku.isPreV11() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                if (Shizuku.shouldShowRequestPermissionRationale()) State.DENIED else State.NO_PERMISSION
            else -> null
        }

        fun requestPermission() {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }.onFailure { Log.w(TAG, "requestPermission", it) }
        }
    }
}
