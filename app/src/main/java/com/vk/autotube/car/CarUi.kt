package com.vk.autotube.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.vk.autotube.Bridge
import com.vk.autotube.CarCommand
import com.vk.autotube.Library
import com.vk.autotube.Palette
import com.vk.autotube.R
import com.vk.autotube.Video
import com.vk.autotube.circle
import com.vk.autotube.clipToRounded
import com.vk.autotube.dp
import com.vk.autotube.dpf
import com.vk.autotube.label
import com.vk.autotube.pressable
import com.vk.autotube.rounded
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything drawn on the car screen.
 *
 * HOME  : native home screen with recently played videos.
 * WEB   : the YouTube mobile site, for search results and browsing.
 * WATCH : the video fills the whole screen, with our own large controls on top.
 *         The YouTube page is still playing underneath; injected CSS hides
 *         everything except the <video> element.
 */
@SuppressLint("SetJavaScriptEnabled")
class CarUi(
    private val ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val onModeChanged: () -> Unit,
) {
    enum class Mode { HOME, WEB, WATCH }

    val root = FrameLayout(ctx)

    var mode = Mode.HOME
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var moving = false
    private var currentId: String? = null
    private var watchOrigin = Mode.HOME
    private var paused = false
    private var loading = true
    private var duration = 0.0
    private var pageTitle: String? = null
    private var insets = Rect()

    // Playback position, seeking and resume
    private var lastTime = 0.0
    private var pendingStart = 0
    private var scrubTarget: Double? = null
    private var seekHoldUntil = 0L
    private var pausedForBackground = false

    // Home
    private val homeScroll = ScrollView(ctx)
    private val homeContent = LinearLayout(ctx)
    private val recentTitle = ctx.label("Recently played", 18f, Palette.TEXT, 700)
    private val grid = LinearLayout(ctx)

    // Web
    private val web = WebView(ctx)
    private val fullscreenHost = FrameLayout(ctx)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // Watch
    private val tapCatcher = View(ctx)
    private val controls = FrameLayout(ctx)
    private val titleText = ctx.label("", 20f, Palette.TEXT, 700)
    private val channelText = ctx.label("", 14f, Palette.MUTED, 500)
    private val playButton = ImageView(ctx)
    private val spinner = ProgressBar(ctx)
    private val seekBar = SeekBarView(ctx)
    private val timeText = ctx.label("0:00", 13f, Palette.TEXT, 600)
    private val durationText = ctx.label("", 13f, Palette.MUTED, 500)
    private val adChip = ctx.label("Ad", 13f, Color.BLACK, 700)
    private val skipAd = LinearLayout(ctx)

    // Driving
    private val drivingCover = LinearLayout(ctx)
    private val drivingPlay = ImageView(ctx)

    // Audio focus: tells Android (and so Android Auto) that AutoTube is the media
    // playing now, so other music apps like Spotify pause instead of covering it.
    private val audioManager: AudioManager? = ctx.getSystemService(AudioManager::class.java)
    private var hasFocus = false
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false
        }
        .build()

    private val libraryListener: () -> Unit = {
        if (!destroyed) {
            rebuildGrid()
            updateTitle()
        }
    }

    private val hideRunnable = Runnable {
        if (!paused && !loading) hideControls()
    }

    private val commitScrub = Runnable {
        val t = scrubTarget
        if (t != null) {
            scrubTarget = null
            seekAbs(t)
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed || mode != Mode.WATCH) return
            js("window.__at.state()") { parseState(it) }
            handler.postDelayed(this, 500)
        }
    }

    init {
        root.setBackgroundColor(Palette.BG)
        buildHome()
        setUpWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        fullscreenHost.setBackgroundColor(Color.BLACK)
        fullscreenHost.visibility = View.GONE
        root.addView(fullscreenHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildWatchControls()
        buildDrivingCover()
        Library.addListener(libraryListener)
        setMode(Mode.HOME, notify = false)
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    fun handle(cmd: CarCommand) {
        when (cmd) {
            is CarCommand.Home -> goHome()
            is CarCommand.Play -> play(cmd.videoId, cmd.start)
            is CarCommand.Browse -> browse(cmd.url)
        }
    }

    fun play(id: String, start: Int = 0) {
        if (destroyed) return
        watchOrigin = if (mode == Mode.HOME) Mode.HOME else Mode.WEB
        currentId = id
        pageTitle = null
        pausedForBackground = false
        Bridge.remember(CarCommand.Play(id, start))
        Library.add(ctx, id)
        exitFullscreen()
        resetPlayer()
        pendingStart = start
        lastTime = start.toDouble()
        updateTitle()
        web.loadUrl(Bridge.watchUrl(id, start))
        setMode(Mode.WATCH)
    }

    /** Remembers where the video is, so it resumes there when AutoTube comes back. */
    fun saveResumePoint() {
        val id = currentId ?: return
        if (destroyed || mode != Mode.WATCH) return
        val at = if (pendingStart > 0) pendingStart else lastTime.toInt()
        Bridge.remember(CarCommand.Play(id, max(0, at - 2)))
    }

    /** Another app (like Google Maps) took the car screen: pause and step aside. */
    fun pauseForBackground() {
        if (destroyed || mode != Mode.WATCH) return
        saveResumePoint()
        if (!paused) {
            pausedForBackground = true
            paused = true
            setPausedIcon()
            js("window.__at.pause()")
        }
        abandonAudioFocus()
    }

    /** AutoTube is back on the car screen: carry on if we paused it. */
    fun resumeFromBackground() {
        if (destroyed || !pausedForBackground) return
        pausedForBackground = false
        if (mode != Mode.WATCH) return
        paused = false
        setPausedIcon()
        requestAudioFocus()
        js("window.__at.play()")
        showControls()
    }

    fun browse(url: String) {
        if (destroyed) return
        Bridge.remember(CarCommand.Browse(url))
        exitFullscreen()
        web.loadUrl(url)
        setMode(Mode.WEB)
    }

    fun goHome() {
        if (destroyed) return
        Bridge.remember(CarCommand.Home)
        exitFullscreen()
        currentId = null
        web.loadUrl("about:blank")
        setMode(Mode.HOME)
    }

    fun back() {
        if (destroyed) return
        when (mode) {
            Mode.HOME -> {}
            Mode.WEB -> {
                if (customView != null) {
                    exitFullscreen()
                } else if (web.canGoBack()) {
                    web.goBack()
                } else {
                    goHome()
                }
            }
            Mode.WATCH -> {
                if (watchOrigin == Mode.WEB && web.canGoBack()) web.goBack() else goHome()
            }
        }
    }

    /** Page up / page down buttons from the car. */
    fun page(direction: Int) {
        if (destroyed || moving) return
        when (mode) {
            Mode.HOME -> homeScroll.smoothScrollBy(0, direction * screenH * 6 / 10)
            Mode.WEB -> js("window.scrollBy(0, $direction * window.innerHeight * 0.7)")
            Mode.WATCH -> {}
        }
    }

    /** Drag gestures on the car screen. */
    fun scroll(dx: Float, dy: Float) {
        if (destroyed || moving) return
        when (mode) {
            Mode.HOME -> homeScroll.scrollBy(0, dy.roundToInt())
            Mode.WEB -> {
                val d = ctx.resources.displayMetrics.density
                js("window.scrollBy(${(dx / d).roundToInt()}, ${(dy / d).roundToInt()})")
            }
            Mode.WATCH -> scrub(dx)
        }
    }

    /** Sideways drag on the video scrubs through it, like dragging the red bar. */
    private fun scrub(dx: Float) {
        if (loading || duration <= 0.0) return
        showControls()
        val width = max(1, seekBar.width)
        val base = scrubTarget ?: lastTime
        val target = (base - dx.toDouble() / width * duration).coerceIn(0.0, duration)
        scrubTarget = target
        seekBar.progress = (target / duration).toFloat()
        timeText.text = formatTime(target)
        handler.removeCallbacks(commitScrub)
        handler.postDelayed(commitScrub, 350)
    }

    private fun seekAbs(seconds: Double) {
        val t = if (duration > 0.0) seconds.coerceIn(0.0, duration) else max(0.0, seconds)
        lastTime = t
        seekHoldUntil = SystemClock.uptimeMillis() + 1500
        if (duration > 0.0) seekBar.progress = (t / duration).toFloat()
        timeText.text = formatTime(t)
        js("window.__at.seekAbs($t)")
        scheduleHide()
    }

    /** A tap on the car screen becomes a real touch on whatever is under it. */
    fun tap(x: Float, y: Float) {
        if (destroyed) return
        val target = root.rootView
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    fun setMoving(isMoving: Boolean) {
        moving = isMoving
        updateDriving()
    }

    fun applyInsets(stable: Rect) {
        if (stable.width() <= 0 || stable.height() <= 0) return
        insets = Rect(
            max(stable.left, 0),
            max(stable.top, 0),
            max(screenW - stable.right, 0),
            max(screenH - stable.bottom, 0),
        )
        val side = ctx.dp(28)
        homeContent.setPadding(insets.left + side, insets.top + ctx.dp(24), insets.right + side, insets.bottom + ctx.dp(24))
        applyWebLayout()
        rebuildGrid()
    }

    fun destroy() {
        if (destroyed) return
        abandonAudioFocus()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        Library.removeListener(libraryListener)
        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Modes
    // ------------------------------------------------------------------

    private fun setMode(m: Mode, notify: Boolean = true) {
        val changed = m != mode
        mode = m
        homeScroll.visibility = if (m == Mode.HOME) View.VISIBLE else View.GONE
        web.visibility = if (m == Mode.HOME) View.INVISIBLE else View.VISIBLE
        tapCatcher.visibility = if (m == Mode.WATCH) View.VISIBLE else View.GONE
        applyWebLayout()
        if (m == Mode.WATCH) {
            requestAudioFocus()
            showControls()
            handler.removeCallbacks(poll)
            handler.post(poll)
        } else {
            abandonAudioFocus()
            hideControlsNow()
            skipAd.visibility = View.GONE
            handler.removeCallbacks(poll)
            handler.removeCallbacks(commitScrub)
            scrubTarget = null
        }
        inject()
        updateDriving()
        if (notify && changed) onModeChanged()
    }

    private fun applyWebLayout() {
        val lp = web.layoutParams as? FrameLayout.LayoutParams ?: return
        if (mode == Mode.WATCH) {
            lp.setMargins(0, 0, 0, 0)
        } else {
            lp.setMargins(insets.left, insets.top, insets.right, insets.bottom)
        }
        web.layoutParams = lp
    }

    private fun onUrlChanged(url: String?) {
        if (destroyed || url == null) return
        if (url.startsWith("about:")) {
            if (mode != Mode.HOME) goHome()
            return
        }
        val id = if (isWatchUrl(url)) Bridge.videoIdFrom(url) else null
        if (id != null) {
            if (id != currentId) {
                currentId = id
                pageTitle = null
                Bridge.remember(CarCommand.Play(id))
                Library.add(ctx, id)
                resetPlayer()
                updateTitle()
            }
            if (mode != Mode.WATCH) {
                watchOrigin = Mode.WEB
                setMode(Mode.WATCH)
            } else {
                inject()
            }
        } else if (mode == Mode.WATCH) {
            currentId = null
            setMode(Mode.WEB)
        }
    }

    private fun isWatchUrl(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host ?: return false
        val path = u.path ?: ""
        return host.contains("youtube.com") && (path.startsWith("/watch") || path.startsWith("/shorts/"))
    }

    // ------------------------------------------------------------------
    // Web view
    // ------------------------------------------------------------------

    private fun setUpWebView() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        // Dark theme on the YouTube mobile site.
        cookies.setCookie("https://m.youtube.com", "PREF=f6=400; Domain=.youtube.com; Path=/")

        web.setBackgroundColor(Color.BLACK)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        // Drop the WebView marker so YouTube serves the normal mobile site.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Block "open in app" links (intent://, vnd.youtube://) so we stay on the car screen.
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                inject()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                onUrlChanged(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fullscreenHost.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                fullscreenHost.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                fullscreenHost.removeAllViews()
                fullscreenHost.visibility = View.GONE
                customView = null
                customCallback = null
            }
        }
    }

    private fun exitFullscreen() {
        val cb = customCallback ?: return
        cb.onCustomViewHidden()
        fullscreenHost.removeAllViews()
        fullscreenHost.visibility = View.GONE
        customView = null
        customCallback = null
    }

    private fun inject() {
        if (destroyed || mode == Mode.HOME) return
        js("window.__at.on=${mode == Mode.WATCH};window.__at.apply();")
    }

    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        if (destroyed) return
        val callback: ValueCallback<String>? =
            if (cb == null) null else ValueCallback<String> { value -> cb(value) }
        web.evaluateJavascript(CORE_JS + "\n" + code, callback)
    }

    // ------------------------------------------------------------------
    // Home screen
    // ------------------------------------------------------------------

    private fun buildHome() {
        homeContent.orientation = LinearLayout.VERTICAL
        val side = ctx.dp(28)
        homeContent.setPadding(side, ctx.dp(24), side, ctx.dp(24))
        homeScroll.isVerticalScrollBarEnabled = false
        homeScroll.overScrollMode = View.OVER_SCROLL_NEVER
        homeScroll.addView(homeContent, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(homeScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val logo = ImageView(ctx)
        logo.setImageResource(R.drawable.ic_logo)
        val logoLp = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
        logoLp.marginEnd = ctx.dp(14)
        header.addView(logo, logoLp)

        val titles = LinearLayout(ctx)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(ctx.label("AutoTube", 26f, Palette.TEXT, 800))
        titles.addView(ctx.label("Search or share from your phone and it plays here", 14f, Palette.MUTED, 400))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        header.addView(pill(R.drawable.ic_search, "Browse") { browse(Bridge.lastBrowse ?: Bridge.YT_HOME) })
        homeContent.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val titleLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        titleLp.topMargin = ctx.dp(30)
        homeContent.addView(recentTitle, titleLp)

        grid.orientation = LinearLayout.VERTICAL
        val gridLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        gridLp.topMargin = ctx.dp(14)
        homeContent.addView(grid, gridLp)
        rebuildGrid()
    }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val items = Library.recents()
        if (items.isEmpty()) {
            recentTitle.visibility = View.GONE
            grid.addView(emptyCard(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        recentTitle.visibility = View.VISIBLE

        val avail = screenW - homeContent.paddingLeft - homeContent.paddingRight
        val gap = ctx.dp(18)
        val cols = max(2, min(5, (avail + gap) / (ctx.dp(230) + gap)))
        val cardW = max(1, (avail - gap * (cols - 1)) / cols)

        var row: LinearLayout? = null
        for ((i, v) in items.withIndex()) {
            if (i % cols == 0) {
                val r = LinearLayout(ctx)
                r.orientation = LinearLayout.HORIZONTAL
                val rowLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                if (i > 0) rowLp.topMargin = ctx.dp(22)
                grid.addView(r, rowLp)
                row = r
            }
            val lp = LinearLayout.LayoutParams(cardW, WRAP_CONTENT)
            if (i % cols != 0) lp.marginStart = gap
            row?.addView(videoCard(v, cardW), lp)
        }
    }

    private fun videoCard(v: Video, w: Int): View {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.foreground = pressable(null, rounded(Color.WHITE, ctx.dpf(16f)))
        card.setOnClickListener { play(v.id) }

        val thumb = ImageView(ctx)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = rounded(Palette.SURFACE, ctx.dpf(16f))
        thumb.clipToRounded()
        thumb.tag = v.id
        card.addView(thumb, LinearLayout.LayoutParams(w, w * 9 / 16))
        Library.thumbnail(v.id) { bmp ->
            if (bmp != null && thumb.tag == v.id) thumb.setImageBitmap(bmp)
        }

        val title = ctx.label(v.title.ifEmpty { "Loading…" }, 15f, Palette.TEXT, 600)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val titleLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        titleLp.topMargin = ctx.dp(10)
        card.addView(title, titleLp)

        if (v.channel.isNotEmpty()) {
            val channel = ctx.label(v.channel, 13f, Palette.MUTED, 400)
            channel.maxLines = 1
            channel.ellipsize = TextUtils.TruncateAt.END
            val chLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            chLp.topMargin = ctx.dp(2)
            card.addView(channel, chLp)
        }
        return card
    }

    private fun emptyCard(): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = rounded(Palette.SURFACE, ctx.dpf(22f))
        box.setPadding(ctx.dp(32), ctx.dp(40), ctx.dp(32), ctx.dp(40))

        val icon = ImageView(ctx)
        icon.setImageResource(R.drawable.ic_logo)
        box.addView(icon, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))

        val title = ctx.label("Nothing played yet", 22f, Palette.TEXT, 700)
        title.gravity = Gravity.CENTER
        val tLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        tLp.topMargin = ctx.dp(16)
        box.addView(title, tLp)

        val body = ctx.label(
            "On your phone, open a video in YouTube, tap Share and pick AutoTube.\nOr search in the AutoTube app.",
            15f, Palette.MUTED, 400,
        )
        body.gravity = Gravity.CENTER
        val bLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        bLp.topMargin = ctx.dp(8)
        box.addView(body, bLp)
        return box
    }

    private fun pill(icon: Int, text: String, onClick: () -> Unit): View {
        val p = LinearLayout(ctx)
        p.orientation = LinearLayout.HORIZONTAL
        p.gravity = Gravity.CENTER_VERTICAL
        p.setPadding(ctx.dp(16), ctx.dp(11), ctx.dp(20), ctx.dp(11))
        p.background = pressable(rounded(Palette.CHIP, ctx.dpf(24f)))
        p.isClickable = true
        p.setOnClickListener { onClick() }

        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        val ivLp = LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20))
        ivLp.marginEnd = ctx.dp(8)
        p.addView(iv, ivLp)
        p.addView(ctx.label(text, 15f, Palette.TEXT, 600))
        return p
    }

    // ------------------------------------------------------------------
    // Full-screen player controls
    // ------------------------------------------------------------------

    private fun buildWatchControls() {
        tapCatcher.isClickable = true
        tapCatcher.setOnClickListener { showControls() }
        root.addView(tapCatcher, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        controls.isClickable = true
        controls.setOnClickListener { hideControls() }
        controls.visibility = View.GONE

        val topScrim = View(ctx)
        topScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT),
        )
        controls.addView(topScrim, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(150), Gravity.TOP))

        val bottomScrim = View(ctx)
        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT),
        )
        controls.addView(bottomScrim, FrameLayout.LayoutParams(MATCH_PARENT, ctx.dp(170), Gravity.BOTTOM))

        // Top: back button, title, channel. Right side left free for Android Auto's buttons.
        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(ctx.dp(24), ctx.dp(20), ctx.dp(140), 0)
        top.addView(
            circleButton(R.drawable.ic_back, 52, Palette.GLASS) { back() },
            LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)),
        )
        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(ctx.dp(16), 0, 0, 0)
        titleText.maxLines = 1
        titleText.ellipsize = TextUtils.TruncateAt.END
        channelText.maxLines = 1
        channelText.ellipsize = TextUtils.TruncateAt.END
        texts.addView(titleText)
        texts.addView(channelText)
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // Centre: back 10 s, play / pause, forward 10 s.
        val center = LinearLayout(ctx)
        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER_VERTICAL
        center.addView(
            circleButton(R.drawable.ic_rewind, 68, Palette.GLASS) { seek(-10) },
            LinearLayout.LayoutParams(ctx.dp(68), ctx.dp(68)),
        )
        val playLp = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96))
        playLp.marginStart = ctx.dp(40)
        playLp.marginEnd = ctx.dp(40)
        center.addView(circleButton(R.drawable.ic_pause, 96, Palette.ACCENT, playButton) { toggle() }, playLp)
        center.addView(
            circleButton(R.drawable.ic_forward, 68, Palette.GLASS) { seek(10) },
            LinearLayout.LayoutParams(ctx.dp(68), ctx.dp(68)),
        )
        controls.addView(center, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))

        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        spinner.visibility = View.GONE
        controls.addView(spinner, FrameLayout.LayoutParams(ctx.dp(56), ctx.dp(56), Gravity.CENTER))

        // Bottom: ad chip, progress bar, times.
        val bottom = LinearLayout(ctx)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(ctx.dp(28), 0, ctx.dp(28), ctx.dp(22))

        adChip.background = rounded(Color.parseColor("#FFD23F"), ctx.dpf(12f))
        adChip.setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        adChip.visibility = View.GONE
        val adLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        adLp.bottomMargin = ctx.dp(4)
        bottom.addView(adChip, adLp)

        // Tall touch area (56dp) around a thin bar, so taps and drags land easily.
        seekBar.onSeek = { f ->
            if (duration > 0.0) seekAbs(f * duration)
        }
        bottom.addView(seekBar, LinearLayout.LayoutParams(MATCH_PARENT, ctx.dp(56)))

        val times = LinearLayout(ctx)
        times.orientation = LinearLayout.HORIZONTAL
        times.addView(timeText)
        times.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        times.addView(durationText)
        bottom.addView(times, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        controls.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Skip ad floats on its own, so it shows even while the controls are hidden.
        skipAd.orientation = LinearLayout.HORIZONTAL
        skipAd.gravity = Gravity.CENTER_VERTICAL
        skipAd.background = pressable(rounded(Color.argb(225, 20, 22, 26), ctx.dpf(28f)))
        skipAd.setPadding(ctx.dp(22), ctx.dp(14), ctx.dp(18), ctx.dp(14))
        skipAd.isClickable = true
        skipAd.visibility = View.GONE
        skipAd.setOnClickListener {
            js("window.__at.skip()")
            skipAd.visibility = View.GONE
        }
        skipAd.addView(ctx.label("Skip ad", 17f, Palette.TEXT, 700))
        val skipIcon = ImageView(ctx)
        skipIcon.setImageResource(R.drawable.ic_forward)
        val skipIconLp = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22))
        skipIconLp.marginStart = ctx.dp(8)
        skipAd.addView(skipIcon, skipIconLp)
        val skipLp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        skipLp.setMargins(0, 0, ctx.dp(28), ctx.dp(120))
        root.addView(skipAd, skipLp)
    }

    private fun circleButton(
        icon: Int,
        sizeDp: Int,
        bg: Int,
        iv: ImageView = ImageView(ctx),
        onClick: () -> Unit,
    ): ImageView {
        iv.setImageResource(icon)
        val pad = ctx.dp(sizeDp) * 26 / 100
        iv.setPadding(pad, pad, pad, pad)
        iv.background = pressable(circle(bg))
        iv.isClickable = true
        iv.setOnClickListener {
            onClick()
            scheduleHide()
        }
        return iv
    }

    private fun showControls() {
        if (mode != Mode.WATCH) return
        controls.animate().cancel()
        if (controls.visibility != View.VISIBLE) {
            controls.alpha = 0f
            controls.visibility = View.VISIBLE
        }
        controls.animate().alpha(1f).setDuration(180).start()
        scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4000)
    }

    private fun hideControls() {
        handler.removeCallbacks(hideRunnable)
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(220).withEndAction { controls.visibility = View.GONE }.start()
    }

    private fun hideControlsNow() {
        handler.removeCallbacks(hideRunnable)
        controls.animate().cancel()
        controls.alpha = 0f
        controls.visibility = View.GONE
    }

    private fun requestAudioFocus() {
        if (hasFocus) return
        val result = audioManager?.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasFocus) return
        audioManager?.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun toggle() {
        paused = !paused
        if (!paused) requestAudioFocus()
        setPausedIcon()
        js("window.__at.toggle()")
        if (paused) showControls() else scheduleHide()
    }

    private fun seek(seconds: Int) {
        if (loading) return
        seekAbs((scrubTarget ?: lastTime) + seconds)
    }

    private fun setPausedIcon() {
        val res = if (paused) R.drawable.ic_play else R.drawable.ic_pause
        playButton.setImageResource(res)
        drivingPlay.setImageResource(res)
    }

    private fun resetPlayer() {
        paused = false
        loading = true
        duration = 0.0
        seekBar.progress = 0f
        timeText.text = "0:00"
        durationText.text = ""
        adChip.visibility = View.GONE
        skipAd.visibility = View.GONE
        lastTime = 0.0
        pendingStart = 0
        scrubTarget = null
        seekHoldUntil = 0L
        handler.removeCallbacks(commitScrub)
        spinner.visibility = View.VISIBLE
        playButton.visibility = View.INVISIBLE
        setPausedIcon()
    }

    private fun parseState(raw: String?) {
        if (destroyed || raw == null || raw == "null") return
        try {
            val s = JSONTokener(raw).nextValue() as? String ?: return
            val o = JSONObject(s)
            val ok = o.optBoolean("ok", false)
            val t = o.optDouble("t", 0.0)
            val d = o.optDouble("d", 0.0)
            val p = o.optBoolean("p", true)
            val ad = o.optBoolean("ad", false)
            val skippable = o.optBoolean("sk", false)

            val nowLoading = !ok || d <= 0.0
            if (nowLoading != loading) {
                loading = nowLoading
                spinner.visibility = if (loading) View.VISIBLE else View.GONE
                playButton.visibility = if (loading) View.INVISIBLE else View.VISIBLE
                if (!loading) scheduleHide()
            }
            if (!ok) return

            duration = d
            durationText.text = if (d > 0.0) formatTime(d) else ""

            // Start time from the link (?t=...), applied once the real video (not an ad) is ready.
            if (pendingStart > 0 && !loading && !ad && d > pendingStart) {
                val target = pendingStart.toDouble()
                pendingStart = 0
                if (kotlin.math.abs(t - target) > 3.0) seekAbs(target)
            }

            // Don't let the 500 ms poll fight a seek the user just made.
            val holding = scrubTarget != null || seekBar.dragging || SystemClock.uptimeMillis() < seekHoldUntil
            if (!holding) {
                if (!ad) lastTime = t
                seekBar.progress = if (d > 0.0) (t / d).toFloat() else 0f
                timeText.text = formatTime(t)
            }

            adChip.visibility = if (ad) View.VISIBLE else View.GONE
            skipAd.visibility = if (skippable && mode == Mode.WATCH && !moving) View.VISIBLE else View.GONE

            if (p != paused) {
                paused = p
                setPausedIcon()
                if (paused) showControls() else scheduleHide()
            }

            val title = o.optString("title").removeSuffix(" - YouTube").trim()
            if (title.isNotEmpty() && title != "YouTube" && title != pageTitle) {
                pageTitle = title
                updateTitle()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateTitle() {
        val v = currentId?.let { Library.find(it) }
        val title = v?.title?.takeIf { it.isNotEmpty() } ?: pageTitle ?: ""
        titleText.text = title
        channelText.text = v?.channel ?: ""
    }

    private fun formatTime(seconds: Double): String {
        val total = max(0L, seconds.toLong())
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    // ------------------------------------------------------------------
    // Driving lock
    // ------------------------------------------------------------------

    private fun buildDrivingCover() {
        drivingCover.orientation = LinearLayout.VERTICAL
        drivingCover.gravity = Gravity.CENTER
        drivingCover.setBackgroundColor(Palette.BG)
        drivingCover.isClickable = true
        drivingCover.visibility = View.GONE

        val title = ctx.label("Paused for driving", 28f, Palette.TEXT, 800)
        title.gravity = Gravity.CENTER
        drivingCover.addView(title)

        val body = ctx.label("Video comes back when you stop. Audio keeps playing.", 16f, Palette.MUTED, 400)
        body.gravity = Gravity.CENTER
        val bLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        bLp.topMargin = ctx.dp(8)
        drivingCover.addView(body, bLp)

        val playLp = LinearLayout.LayoutParams(ctx.dp(84), ctx.dp(84))
        playLp.topMargin = ctx.dp(32)
        drivingCover.addView(circleButton(R.drawable.ic_pause, 84, Palette.CHIP, drivingPlay) { toggle() }, playLp)

        root.addView(drivingCover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun updateDriving() {
        drivingCover.visibility = if (moving) View.VISIBLE else View.GONE
        if (moving) skipAd.visibility = View.GONE
        drivingPlay.visibility = if (mode == Mode.WATCH) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * Injected into every YouTube page. Idempotent: a second run only re-applies.
         * In watch mode everything on the page is made invisible except the <video>,
         * which is pinned edge to edge.
         */
        private const val CORE_JS = """
(function(){
  if (window.__at) { window.__at.apply(); return; }
  var css =
    'html.at-watch, html.at-watch body { background:#000 !important; overflow:hidden !important; }' +
    'html.at-watch body { visibility:hidden !important; }' +
    'html.at-watch video { visibility:visible !important; position:fixed !important; left:0 !important; top:0 !important;' +
    ' width:100vw !important; height:100vh !important; max-width:none !important; max-height:none !important;' +
    ' margin:0 !important; object-fit:contain !important; background:#000 !important; transform:none !important;' +
    ' z-index:2147483647 !important; }' +
    'html.at-watch .at-chain { transform:none !important; filter:none !important; contain:none !important;' +
    ' will-change:auto !important; perspective:none !important; backdrop-filter:none !important; }';
  var st = document.createElement('style');
  st.textContent = css;
  (document.head || document.documentElement).appendChild(st);

  function vid() {
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) { if (vs[i].currentSrc || vs[i].src) return vs[i]; }
    return vs.length ? vs[0] : null;
  }
  function chain(v) {
    var old = document.querySelectorAll('.at-chain');
    for (var i = 0; i < old.length; i++) old[i].classList.remove('at-chain');
    var p = v ? v.parentElement : null;
    while (p && p !== document.documentElement) { p.classList.add('at-chain'); p = p.parentElement; }
  }
  // YouTube autoplays muted when there was no finger tap, and re-mutes the
  // element itself. Unmute through its own player API and keep it unmuted.
  function unmute(v) {
    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    try {
      if (mp && mp.isMuted && mp.isMuted()) mp.unMute();
      if (mp && mp.getVolume && mp.getVolume() < 5) mp.setVolume(100);
    } catch (e) {}
    if (v.muted) {
      var b = document.querySelector('.ytp-unmute, button.ytp-unmute, .ytp-unmute-inner');
      if (b) { try { b.click(); } catch (e) {} }
      v.muted = false;
    }
    if (v.volume === 0) v.volume = 1;
  }
  function player() {
    return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
  }
  function isAd() {
    var mp = player();
    if (mp && mp.classList && (mp.classList.contains('ad-showing') || mp.classList.contains('ad-interrupting'))) return true;
    return !!document.querySelector('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-module .ytp-ad-text');
  }
  // The skip button only exists in the page once YouTube allows skipping.
  // Our CSS makes it invisible, but it still has a size, which is what we check.
  function skipButton() {
    var list = document.querySelectorAll(
      '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, ' +
      '.ytp-ad-skip-button-slot button, .ytp-ad-skip-button-container button, ' +
      'button[class*="skip-ad"], button[class*="ad-skip"], button[id^="skip-button"], ' +
      '[id^="skip-button"] button, button[aria-label^="Skip"]');
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      if (b.disabled) continue;
      var cs = getComputedStyle(b);
      if (cs.display === 'none' || b.offsetWidth === 0 || b.offsetHeight === 0) continue;
      return b;
    }
    return null;
  }
  function press(el) {
    var opts = { bubbles: true, cancelable: true, view: window };
    try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (e) {}
    try { el.click(); } catch (e) {}
  }
  // Seek through YouTube's own player when it's there; it handles buffering better.
  function seekAbs(t) {
    var v = vid();
    if (!v) return;
    t = Math.max(0, t);
    if (isFinite(v.duration) && v.duration > 0) t = Math.min(t, v.duration - 0.5);
    var mp = player();
    try { if (mp && mp.seekTo && !isAd()) { mp.seekTo(t, true); return; } } catch (e) {}
    v.currentTime = t;
  }

  window.__at = {
    on: false,
    apply: function() {
      var h = document.documentElement;
      if (!this.on) { h.classList.remove('at-watch'); return; }
      h.classList.add('at-watch');
      var v = vid();
      if (!v) return;
      chain(v);
      unmute(v);
      if (!v.__atStarted) {
        v.__atStarted = true;
        if (v.paused) { var pr = v.play(); if (pr && pr.catch) pr.catch(function(){}); }
      }
    },
    state: function() {
      var v = vid();
      if (!v) return JSON.stringify({ ok: false });
      return JSON.stringify({
        ok: true,
        t: v.currentTime || 0,
        d: isFinite(v.duration) ? v.duration : 0,
        p: v.paused,
        ad: isAd(),
        sk: !!skipButton(),
        title: document.title
      });
    },
    toggle: function() { var v = vid(); if (!v) return; if (v.paused) { unmute(v); v.play(); } else { v.pause(); } },
    play: function() { var v = vid(); if (!v) return; unmute(v); var pr = v.play(); if (pr && pr.catch) pr.catch(function(){}); },
    pause: function() { var v = vid(); if (v) v.pause(); },
    seekAbs: function(t) { seekAbs(t); },
    // Press YouTube's skip button. If YouTube ignores the scripted press,
    // jump to the end of the (skippable) ad, which ends it the same way.
    skip: function() {
      var b = skipButton();
      if (!b) return;
      press(b);
      setTimeout(function() {
        var v = vid();
        if (v && isAd() && skipButton() && isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      }, 600);
    }
  };
  setInterval(function(){ window.__at.apply(); }, 500);
})();
"""
    }
}
