package com.vk.autotube

import android.net.Uri
import android.os.Handler
import android.os.Looper

/** What the phone asks the car screen to do. */
sealed class CarCommand {
    object Home : CarCommand()
    data class Play(val videoId: String) : CarCommand()
    data class Browse(val url: String) : CarCommand()
}

/**
 * Link between the phone screen and the car screen.
 * Both run in the same app process, so a simple singleton is enough.
 */
object Bridge {
    const val YT_HOME = "https://m.youtube.com/"

    private val main = Handler(Looper.getMainLooper())

    /** Last state of the car screen, so it can resume after a reconnect. */
    var last: CarCommand = CarCommand.Home
        private set

    /** Last search or page opened, for the car's Browse button. */
    var lastBrowse: String? = null
        private set

    var listener: ((CarCommand) -> Unit)? = null

    fun send(cmd: CarCommand) {
        main.post {
            remember(cmd)
            listener?.invoke(cmd)
        }
    }

    fun remember(cmd: CarCommand) {
        last = cmd
        if (cmd is CarCommand.Browse) lastBrowse = cmd.url
    }

    private val urlRegex = Regex("""https?://\S+""")
    private val idPatterns = listOf(
        Regex("""youtu\.be/([\w-]{11})"""),
        Regex("""[?&]v=([\w-]{11})"""),
        Regex("""/shorts/([\w-]{11})"""),
        Regex("""/live/([\w-]{11})"""),
        Regex("""/embed/([\w-]{11})"""),
    )

    fun videoIdFrom(text: String): String? {
        for (p in idPatterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** Turns a shared link, a pasted link, or plain search words into a command. */
    fun parse(input: String): CarCommand {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return CarCommand.Browse(YT_HOME)
        val link = urlRegex.find(trimmed)?.value
        if (link != null) {
            if (link.contains("youtu")) {
                val id = videoIdFrom(link)
                if (id != null) return CarCommand.Play(id)
                return CarCommand.Browse(
                    link.replace(Regex("""https?://(www\.|music\.)?youtube\.com"""), "https://m.youtube.com")
                )
            }
            return CarCommand.Browse(link)
        }
        return CarCommand.Browse(searchUrl(trimmed))
    }

    fun searchUrl(query: String) = "https://m.youtube.com/results?search_query=" + Uri.encode(query)

    fun watchUrl(id: String) = "https://m.youtube.com/watch?v=$id"
}
