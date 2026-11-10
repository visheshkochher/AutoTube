package com.vk.autotube

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class Video(val id: String, val title: String, val channel: String)

/**
 * Recently played videos, shared by the phone and car screens.
 * Titles come from YouTube's public oEmbed endpoint, thumbnails from i.ytimg.com.
 * No API key needed.
 */
object Library {
    private const val MAX = 24

    private var prefs: SharedPreferences? = null
    private val items = mutableListOf<Video>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val pendingMeta = mutableSetOf<String>()
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<String, Bitmap>(40)

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("autotube", Context.MODE_PRIVATE)
        prefs = p
        try {
            val arr = JSONArray(p.getString("recents", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Video(o.getString("id"), o.optString("title"), o.optString("channel")))
            }
        } catch (_: Exception) {
        }
        items.filter { it.title.isEmpty() }.forEach { fetchMeta(it.id) }
    }

    fun recents(): List<Video> = items.toList()

    fun find(id: String): Video? = items.firstOrNull { it.id == id }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Moves a video to the top of the list, fetching its title if needed. */
    fun add(ctx: Context, id: String) {
        init(ctx)
        val existing = find(id)
        if (existing != null && items.firstOrNull()?.id == id) {
            if (existing.title.isEmpty()) fetchMeta(id)
            return
        }
        items.removeAll { it.id == id }
        items.add(0, existing ?: Video(id, "", ""))
        while (items.size > MAX) items.removeAt(items.size - 1)
        save()
        notifyChanged()
        if (existing == null || existing.title.isEmpty()) fetchMeta(id)
    }

    fun thumbnail(id: String, cb: (Bitmap?) -> Unit) {
        val cached = thumbs.get(id)
        if (cached != null) {
            cb(cached)
            return
        }
        io.execute {
            val bytes = httpGet("https://i.ytimg.com/vi/$id/hqdefault.jpg")
            val bmp = try {
                if (bytes == null) {
                    null
                } else {
                    val opts = BitmapFactory.Options()
                    opts.inPreferredConfig = Bitmap.Config.RGB_565
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (bmp != null) thumbs.put(id, bmp)
                cb(bmp)
            }
        }
    }

    private fun fetchMeta(id: String) {
        if (!pendingMeta.add(id)) return
        io.execute {
            var title = ""
            var channel = ""
            try {
                val target = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                val bytes = httpGet("https://www.youtube.com/oembed?format=json&url=$target")
                if (bytes != null) {
                    val o = JSONObject(String(bytes, Charsets.UTF_8))
                    title = o.optString("title")
                    channel = o.optString("author_name")
                }
            } catch (_: Exception) {
            }
            main.post {
                pendingMeta.remove(id)
                if (title.isNotEmpty()) {
                    val i = items.indexOfFirst { it.id == id }
                    if (i >= 0) {
                        items[i] = Video(id, title, channel)
                        save()
                        notifyChanged()
                    }
                }
            }
        }
    }

    private fun httpGet(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (v in items) {
            val o = JSONObject()
            o.put("id", v.id)
            o.put("title", v.title)
            o.put("channel", v.channel)
            arr.put(o)
        }
        prefs?.edit()?.putString("recents", arr.toString())?.apply()
    }

    private fun notifyChanged() {
        for (l in listeners.toList()) l()
    }
}
