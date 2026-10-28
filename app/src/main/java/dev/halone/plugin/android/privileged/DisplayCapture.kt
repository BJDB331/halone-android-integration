package dev.halone.plugin.android.privileged

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * A small still of a virtual display, for recent apps' previews, through the
 * window manager's capture call, which the shell user may make. Secure
 * content comes back as nothing. The API is found by shape, as its types
 * moved in Android 17. Ported from The Unbound Launcher's
 * HeadlessScreenCapture (MIT).
 */
@SuppressLint("PrivateApi")
internal class DisplayCapture {
    private val windows: Any
    private val capture: Method
    private val newBuilder: java.lang.reflect.Constructor<*>
    private val frameScale: Method
    private val build: Method
    private val newListener: Method
    private val getBuffer: Method

    init {
        val windowApi = Class.forName("android.view.IWindowManager")
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        windows = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        capture = windowApi.methods.single { method ->
            val params = method.parameterTypes
            method.name == "captureDisplay" && params.size == 3 && params[0] == Int::class.javaPrimitiveType &&
                params[1].simpleName == "CaptureArgs" && params[2].simpleName == "ScreenCaptureListener"
        }
        val args = capture.parameterTypes[1]
        val root = args.declaringClass ?: error("unexpected capture API")
        val builder = Class.forName(args.name + "\$Builder")
        newBuilder = builder.getConstructor()
        frameScale = builder.getMethod("setFrameScale", Float::class.javaPrimitiveType)
        build = builder.getMethod("build")
        newListener = root.getMethod("createSyncCaptureListener")
        check(args.isAssignableFrom(build.returnType) && capture.parameterTypes[2].isAssignableFrom(newListener.returnType)) {
            "incompatible capture API"
        }
        getBuffer = newListener.returnType.getMethod("getBuffer")
    }

    /** JPEG bytes, or null for nothing captured or protected content. */
    fun jpeg(displayId: Int, widthPx: Int, heightPx: Int, maxSidePx: Int): ByteArray? {
        var hardware: Bitmap? = null
        var buffer: HardwareBuffer? = null
        try {
            val builder = newBuilder.newInstance()
            frameScale.invoke(builder, minOf(1f, maxSidePx.toFloat() / maxOf(widthPx, heightPx)))
            val listener = newListener.invoke(null)
            capture.invoke(windows, displayId, build.invoke(builder), listener)
            val result = getBuffer.invoke(listener) ?: return null
            val type = getBuffer.returnType
            buffer = type.getMethod("getHardwareBuffer").invoke(result) as HardwareBuffer? ?: return null
            if (type.getMethod("containsSecureLayers").invoke(result) as Boolean) return null
            hardware = type.getMethod("asBitmap").invoke(result) as Bitmap? ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            return ByteArrayOutputStream().use { out ->
                software.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                software.recycle()
                out.toByteArray()
            }
        } finally {
            hardware?.recycle()
            buffer?.close()
        }
    }

    private companion object {
        const val JPEG_QUALITY = 80
    }
}
