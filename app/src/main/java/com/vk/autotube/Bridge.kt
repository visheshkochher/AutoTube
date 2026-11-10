package com.vk.autotube

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** One turn instruction mirrored from Google Maps' navigation notification. */
data class NavInfo(
    val title: String?,
    val text: String?,
    val sub: String?,
    val icon: Drawable?,
)

/**
 * Shared state between the phone screen, the notification listener and the car screen.
 * Everything runs in one app process, so a simple singleton is enough.
 */
object Bridge {
    const val HOME = "https://m.youtube.com/"

    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    var url: String = HOME
        private set
    var nav: NavInfo? = null
        private set

    var onUrl: ((String) -> Unit)? = null
    var onNav: ((NavInfo?) -> Unit)? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("autotube", Context.MODE_PRIVATE)
        prefs = p
        url = p.getString("url", HOME) ?: HOME
    }

    fun open(ctx: Context, newUrl: String) {
        init(ctx)
        url = newUrl
        prefs?.edit()?.putString("url", newUrl)?.apply()
        main.post { onUrl?.invoke(newUrl) }
    }

    fun postNav(info: NavInfo?) {
        main.post {
            nav = info
            onNav?.invoke(info)
        }
    }

    private val urlRegex = Regex("""https?://\S+""")
    private val idPatterns = listOf(
        Regex("""youtu\.be/([\w-]{11})"""),
        Regex("""[?&]v=([\w-]{11})"""),
        Regex("""/shorts/([\w-]{11})"""),
        Regex("""/live/([\w-]{11})"""),
        Regex("""/embed/([\w-]{11})"""),
    )

    /** Turns a shared link, a pasted link, or plain search words into a mobile YouTube URL. */
    fun youtubeUrlFrom(input: String): String {
        val trimmed = input.trim()
        val link = urlRegex.find(trimmed)?.value
        if (link != null && link.contains("youtu")) {
            for (p in idPatterns) {
                val id = p.find(link)?.groupValues?.get(1)
                if (id != null) return "https://m.youtube.com/watch?v=$id"
            }
            return link.replace(Regex("""https?://(www\.|music\.)?youtube\.com"""), "https://m.youtube.com")
        }
        if (link != null) return link
        if (trimmed.isEmpty()) return HOME
        return "https://m.youtube.com/results?search_query=" + Uri.encode(trimmed)
    }
}
