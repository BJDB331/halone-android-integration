package dev.halone.plugin.android.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The phone's own panel off while the glasses stay on: a leased session, so
 * the phone screen always comes back. It does if the caller stops renewing,
 * if its process dies, if the glasses' display goes, or if this helper dies.
 * Android itself is never put to sleep, and the power button restores the
 * panel too.
 *
 * The panel is switched by a [Watchdog] process of its own, which restores
 * it when its input closes, so even this helper dying cannot leave the phone
 * dark. Ported from The Unbound Launcher's PhoneScreenPower (MIT).
 *
 * Runs in the Shizuku user service; the watchdog is started from the same
 * APK with app_process, so it runs as the same shell (or root) user.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class PhoneScreen(context: Context) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var watchdog: Process? = null
    private var commands: OutputStreamWriter? = null
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var glassesId = Display.INVALID_DISPLAY
    private var renewed = 0L
    private val leaseCheck = Runnable { check() }

    init {
        displays.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = check()
            override fun onDisplayRemoved(displayId: Int) = check()
        }, main)
    }

    @Synchronized
    fun request(command: Int, token: IBinder, glassesDisplayId: Int) {
        when (command) {
            OFF -> {
                check(owner == null) { "the phone screen is already off" }
                requireGlasses(glassesDisplayId)
                // The phone's panel by its address: display ordering is not
                // stable, and the first physical id could be the glasses.
                val phone = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: error("no built-in display")
                check(Display::class.java.getMethod("getType").invoke(phone) as Int == TYPE_INTERNAL) { "no built-in display" }
                val address = Display::class.java.getMethod("getAddress").invoke(phone)!!
                val physical = address.javaClass.getMethod("getPhysicalDisplayId").invoke(address) as Long
                val classpath = System.getenv("CLASSPATH")?.takeIf { it.isNotEmpty() }
                    ?: System.getProperty("java.class.path")?.takeIf { it.isNotEmpty() }
                    ?: error("helper classpath unavailable")
                val process = ProcessBuilder("/system/bin/app_process", "/", Watchdog::class.java.name, physical.toString())
                    .apply { environment()["CLASSPATH"] = classpath }
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                watchdog = process
                commands = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
                owner = token
                val recipient = IBinder.DeathRecipient { synchronized(this) { if (owner === token) restore() } }
                death = recipient
                try {
                    token.linkToDeath(recipient, 0)
                    await("READY")
                    send("OFF")
                    await("OFF")
                    glassesId = glassesDisplayId
                    renewed = SystemClock.elapsedRealtime()
                    main.postDelayed(leaseCheck, CHECK_MS)
                } catch (e: Exception) {
                    restore()
                    throw e
                }
            }
            RENEW -> {
                val current = owner ?: throw IllegalStateException("the phone screen session expired")
                if (current !== token) throw SecurityException("a different session owns the phone screen")
                requireGlasses(glassesId)
                if (watchdog?.isAlive != true) {
                    restore()
                    throw IllegalStateException("the phone screen watchdog stopped")
                }
                send("PING")
                renewed = SystemClock.elapsedRealtime()
            }
            ON -> {
                if (owner == null) return
                if (owner !== token) throw SecurityException("a different session owns the phone screen")
                if (!restore()) throw IllegalStateException("couldn't turn the phone screen back on; press the power button")
            }
            else -> throw IllegalArgumentException("invalid phone screen command")
        }
    }

    private fun requireGlasses(id: Int) {
        val display = displays.getDisplay(id)
        check(
            id != Display.DEFAULT_DISPLAY && display != null && display.state == Display.STATE_ON &&
                Display::class.java.getMethod("getType").invoke(display) as Int == TYPE_EXTERNAL,
        ) { "the glasses' display isn't connected" }
    }

    @Synchronized
    private fun check() {
        val current = owner ?: return
        try {
            requireGlasses(glassesId)
            if (!current.isBinderAlive || SystemClock.elapsedRealtime() - renewed > LEASE_MS || watchdog?.isAlive != true) {
                restore()
                return
            }
        } catch (e: Exception) {
            restore()
            return
        }
        main.removeCallbacks(leaseCheck)
        main.postDelayed(leaseCheck, CHECK_MS)
    }

    private fun send(value: String) {
        commands?.apply {
            write(value + "\n")
            flush()
        }
    }

    private fun await(expected: String) {
        // app_process can print vendor diagnostics before the answer.
        val process = watchdog ?: error("no watchdog")
        val input = process.inputStream
        val deadline = SystemClock.elapsedRealtime() + ANSWER_MS
        val line = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            while (input.available() > 0) {
                val ch = input.read()
                if (ch == '\n'.code) {
                    if (line.toString() == expected) return
                    if (line.startsWith("ERROR")) throw IllegalStateException(line.toString())
                    line.setLength(0)
                } else if (line.length < 1024) {
                    line.append(ch.toChar())
                }
            }
            if (!process.isAlive) break
            Thread.sleep(10)
        }
        throw IllegalStateException("no $expected from the phone screen watchdog")
    }

    private fun restore(): Boolean {
        var restored = true
        val current = owner
        val recipient = death
        if (current != null && recipient != null) runCatching { current.unlinkToDeath(recipient, 0) }
        val process = watchdog
        if (process != null) {
            try {
                send("ON")
                await("ON")
            } catch (e: Exception) {
                // Closing its input restores the panel too.
                runCatching { process.outputStream.close() }
                restored = runCatching { process.waitFor(RESTORE_WAIT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0 }.getOrDefault(false)
            }
        }
        owner = null
        death = null
        watchdog = null
        commands = null
        main.removeCallbacks(leaseCheck)
        return restored
    }

    /** A process of its own that holds the panel off: restores it on ON, a stalled heartbeat, or its input closing. */
    object Watchdog {
        @JvmStatic
        fun main(args: Array<String>) {
            // Started by the Shizuku user service, as shell or root, never anyone else.
            val uid = android.os.Process.myUid()
            if ((uid != SHELL_UID && uid != ROOT_UID) || args.size != 1) throw SecurityException("privileged helper only")
            val surface = Class.forName("android.view.SurfaceControl")
            var control: Class<*> = surface
            try {
                control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            } catch (_: NoSuchMethodException) {
                // Android 14 moved display tokens to the system server's DisplayControl.
                val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val path = System.getenv("SYSTEMSERVERCLASSPATH")?.takeIf { it.isNotEmpty() } ?: "/system/framework/services.jar"
                val loader = factory.getDeclaredMethod(
                    "createClassLoader", String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
                ).invoke(null, path, null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
                control = loader.loadClass("com.android.server.display.DisplayControl")
                Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                    .apply { isAccessible = true }
                    .invoke(Runtime.getRuntime(), control, "android_servers")
            }
            val panel = control.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .invoke(null, args[0].toLong()) as IBinder? ?: error("phone panel unavailable")
            val power = surface.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            val input = LinkedBlockingQueue<String>(16)
            Thread({
                try {
                    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
                    while (true) input.put(reader.readLine() ?: break)
                } catch (_: Exception) {
                } finally {
                    runCatching { input.put("ON") }
                }
            }, "phone-screen-commands").apply { isDaemon = true }.start()
            var off = false
            println("READY")
            System.out.flush()
            try {
                while (true) {
                    val command = input.poll(LEASE_MS, TimeUnit.MILLISECONDS)
                    if (command == null || command == "ON") break
                    if (command == "OFF" && !off) {
                        off = true
                        power.invoke(null, panel, POWER_OFF)
                        println("OFF")
                        System.out.flush()
                    } else if (command != "PING") {
                        throw IllegalArgumentException("invalid watchdog command")
                    }
                }
            } finally {
                if (off) power.invoke(null, panel, POWER_NORMAL)
                println("ON")
                System.out.flush()
            }
            exitProcess(0)
        }
    }

    companion object {
        const val OFF = 1
        const val RENEW = 2
        const val ON = 3
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val TYPE_INTERNAL = 1
        private const val TYPE_EXTERNAL = 2
        private const val POWER_OFF = 0
        private const val POWER_NORMAL = 2
        private const val LEASE_MS = 8_000L
        private const val CHECK_MS = 2_000L
        private const val ANSWER_MS = 3_000L
        private const val RESTORE_WAIT_MS = 3_500L
    }
}
