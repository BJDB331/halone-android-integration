package dev.halone.plugin.android.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.app.ActivityOptions
import dev.halone.plugin.android.IDisplayListener
import dev.halone.plugin.android.IDisplayService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import kotlin.system.exitProcess

/**
 * The Shizuku user service, running as shell or root: every hidden API the
 * plugin needs is called from here, so a platform change breaks one file.
 * Follows scrcpy's `--new-display` path.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "WrongConstant")
class DisplayService(context: Context) : IDisplayService.Stub() {
    private val shell = ShellContext(context)
    private val main = Handler(Looper.getMainLooper())
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val occupied = HashSet<Int>()
    private val orientations = HashMap<Int, Int>()
    @Volatile
    private var listener: IDisplayListener? = null
    private val watch = object : Runnable {
        override fun run() {
            checkTasks()
            if (displays.isNotEmpty()) main.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(shell)
    }

    private val inputManager: Any by lazy { shell.getSystemService(Context.INPUT_SERVICE)!! }

    private val injectInputEvent by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    private val activityManager: Any by lazy {
        Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
    }

    private val windowManager: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    override fun destroy() {
        onMain { displays.values.forEach { it.release() } }
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun createDisplay(name: String, surface: Surface, widthPx: Int, heightPx: Int, densityDpi: Int): Int = onMain {
        val display = displayManager.createVirtualDisplay(name, widthPx, heightPx, densityDpi, surface, displayFlags(), null, main)
            ?: throw IllegalStateException("the display manager refused the display")
        val id = display.display.displayId
        displays[id] = display
        main.removeCallbacks(watch)
        main.postDelayed(watch, WATCH_INTERVAL_MS)
        runCatching { setImePolicy(id, DISPLAY_IME_POLICY_LOCAL) }.onFailure { Log.w(TAG, "IME policy", it) }
        id
    }

    override fun resizeDisplay(displayId: Int, widthPx: Int, heightPx: Int, densityDpi: Int) = onMain {
        displays[displayId]?.resize(widthPx, heightPx, densityDpi)
        Unit
    }

    override fun setSurface(displayId: Int, surface: Surface?) = onMain {
        displays[displayId]?.surface = surface
        Unit
    }

    override fun releaseDisplay(displayId: Int) = onMain {
        displays.remove(displayId)?.release()
        occupied -= displayId
        orientations -= displayId
        Unit
    }

    override fun setListener(listener: IDisplayListener?) {
        this.listener = listener
    }

    override fun startActivity(displayId: Int, intent: Intent): String? {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val result = try {
            startActivityAsUser.invoke(
                activityManager, null, ShellContext.SHELL_PACKAGE, intent, null, null, null, 0, 0, null, options, USER_CURRENT,
            ) as Int
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "startActivityAsUser", e)
            return (e.cause ?: e).toString()
        }
        return if (result < 0) "start failed with $result" else null
    }

    override fun injectMotion(displayId: Int, event: MotionEvent) {
        inject(event, displayId)
        event.recycle()
    }

    override fun injectKey(displayId: Int, event: KeyEvent) {
        if (event.source == InputDevice.SOURCE_UNKNOWN) event.source = InputDevice.SOURCE_KEYBOARD
        inject(event, displayId)
    }

    override fun pressBack(displayId: Int) {
        val now = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val event = KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(event, displayId)
        }
    }

    override fun contextClick(displayId: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val steps = listOf(
            MotionEvent.ACTION_DOWN to MotionEvent.BUTTON_SECONDARY,
            MotionEvent.ACTION_BUTTON_PRESS to MotionEvent.BUTTON_SECONDARY,
            MotionEvent.ACTION_BUTTON_RELEASE to 0,
            MotionEvent.ACTION_UP to 0,
        )
        steps.forEach { (action, buttons) ->
            val event = mouseEvent(now, action, x, y, buttons)
            if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                runCatching { setActionButton.invoke(event, MotionEvent.BUTTON_SECONDARY) }
            }
            inject(event, displayId)
            event.recycle()
        }
    }

    private fun mouseEvent(downTime: Long, action: Int, x: Float, y: Float, buttons: Int): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    private val setActionButton by lazy { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }

    private fun inject(event: InputEvent, displayId: Int) {
        try {
            setDisplayId.invoke(event, displayId)
            injectInputEvent.invoke(inputManager, event, INJECT_MODE_ASYNC)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "inject", e)
        }
    }

    private val startActivityAsUser by lazy {
        activityManager.javaClass.getMethod(
            "startActivityAsUser",
            Class.forName("android.app.IApplicationThread"),
            String::class.java,
            Intent::class.java,
            String::class.java,
            IBinder::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Class.forName("android.app.ProfilerInfo"),
            Bundle::class.java,
            Int::class.javaPrimitiveType,
        )
    }

    private fun checkTasks() {
        for (id in displays.keys) {
            val tasks = runCatching { getAllRootTaskInfosOnDisplay.invoke(activityTaskManager, id) as List<*> }.getOrElse {
                Log.w(TAG, "root tasks", it)
                return
            }
            if (tasks.isNotEmpty()) {
                occupied += id
                val orientation = orientationOf(tasks.first())
                if (orientations.put(id, orientation) != orientation) {
                    runCatching { listener?.onOrientationRequested(id, orientation) }
                }
            } else if (occupied.remove(id)) {
                runCatching { listener?.onDisplayEmptied(id) }
            }
        }
    }

    private fun orientationOf(task: Any?): Int {
        val requested = runCatching { task!!.javaClass.getField("requestedOrientation").getInt(task) }.getOrNull()
            ?: runCatching { (task!!.javaClass.getField("topActivityInfo").get(task) as ActivityInfo?)?.screenOrientation }.getOrNull()
            ?: return ORIENTATION_ANY
        return when (requested) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> ORIENTATION_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> ORIENTATION_PORTRAIT
            else -> ORIENTATION_ANY
        }
    }

    private val activityTaskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val getAllRootTaskInfosOnDisplay by lazy {
        activityTaskManager.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
    }

    private fun setImePolicy(displayId: Int, policy: Int) {
        windowManager.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(windowManager, displayId, policy)
    }

    private fun displayFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? RuntimeException) ?: IllegalStateException(e.cause)
        }
    }

    private companion object {
        const val TAG = "HaloneAndroidApps"
        const val USER_CURRENT = -2
        const val INJECT_MODE_ASYNC = 0
        const val WATCH_INTERVAL_MS = 500L
        const val ORIENTATION_ANY = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val DISPLAY_IME_POLICY_LOCAL = 0

        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
        const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
