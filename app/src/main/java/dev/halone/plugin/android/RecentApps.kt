package dev.halone.plugin.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The phone apps last opened on the glasses, newest first, each with a still
 * of its window as it was last seen. Kept in private storage, so Recents
 * survives a restart; a preview is replaced as the window changes and kept
 * when it closes. After The Unbound Launcher's recent apps (MIT).
 */
class RecentApps(context: Context) {
    data class Entry(val id: String, val label: String, val openedAt: Long)

    private val file = File(context.filesDir, "recent-apps.json")
    private val previews = File(context.filesDir, "recent-previews").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "recent-apps") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Main thread. */
    var entries: List<Entry> = emptyList()
        private set

    init {
        entries = runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                array.getJSONObject(i).let { Entry(it.getString("id"), it.getString("label"), it.getLong("openedAt")) }
            }
        }.getOrDefault(emptyList())
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** [id] was opened: to the front. Main thread. */
    fun opened(id: String, label: String) {
        val kept = entries.filter { it.id != id }
        val next = listOf(Entry(id, label, System.currentTimeMillis())) + kept.take(MAX - 1)
        (kept.drop(MAX - 1)).forEach { gone -> io.execute { previewFile(gone.id).delete() } }
        entries = next
        save()
        changed()
    }

    /** A new still of [id]'s window, JPEG; any thread. */
    fun preview(id: String, jpeg: ByteArray) {
        io.execute {
            val target = previewFile(id)
            val temporary = File(target.path + ".tmp")
            runCatching {
                temporary.writeBytes(jpeg)
                temporary.renameTo(target)
            }
            main.post { changed() }
        }
    }

    /** The last still of [id]'s window, decoded; call off the main thread. */
    fun previewOf(id: String): Bitmap? = previewFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun previewVersion(id: String): Long = previewFile(id).lastModified()

    /** Forgets apps no longer installed. */
    fun retain(installed: Set<String>) {
        val kept = entries.filter { it.id in installed }
        if (kept.size == entries.size) return
        entries.filter { it.id !in installed }.forEach { gone -> io.execute { previewFile(gone.id).delete() } }
        entries = kept
        save()
        changed()
    }

    private fun save() {
        val json = JSONArray().apply {
            entries.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("openedAt", it.openedAt)) }
        }.toString()
        io.execute { runCatching { file.writeText(json) } }
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun previewFile(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(previews, "$name.jpg")
    }

    companion object {
        const val MAX = 10
    }
}
