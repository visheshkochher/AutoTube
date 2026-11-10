package com.vk.autotube.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.vk.autotube.NavInfo
import org.maplibre.android.MapLibre
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/**
 * The car screen layout: [ video pane | map pane ].
 * The map pane is always visible; the video pane blanks out while moving.
 */
@SuppressLint("SetJavaScriptEnabled")
class CarUi(private val ctx: Context) {

    val root = FrameLayout(ctx)

    private val row = LinearLayout(ctx)
    private val videoPane = FrameLayout(ctx)
    private val web = WebView(ctx)
    private val fullscreenHost = FrameLayout(ctx)
    private val movingOverlay = TextView(ctx)

    private val mapPane = FrameLayout(ctx)
    private val mapView: MapView

    private val turnCard = LinearLayout(ctx)
    private val turnIcon = ImageView(ctx)
    private val turnTitle = TextView(ctx)
    private val turnText = TextView(ctx)
    private val turnSub = TextView(ctx)
    private val noNavHint = TextView(ctx)

    private var moving = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var destroyed = false

    init {
        root.setBackgroundColor(Color.BLACK)

        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(8), dp(8), dp(8), dp(8))
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---------- Video pane (left) ----------
        rounded(videoPane, "#000000")
        row.addView(videoPane, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.6f).apply {
            marginEnd = dp(8)
        })

        setUpWebView()
        videoPane.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fullscreenHost.setBackgroundColor(Color.BLACK)
        fullscreenHost.visibility = View.GONE
        videoPane.addView(fullscreenHost, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        movingOverlay.apply {
            text = "Video hidden while driving\nAudio keeps playing"
            setTextColor(Color.parseColor("#B8C2CC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F12"))
            visibility = View.GONE
            isClickable = true
        }
        videoPane.addView(movingOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // ---------- Map pane (right) ----------
        rounded(mapPane, "#10161A")
        row.addView(mapPane, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.4f))

        MapLibre.getInstance(ctx)
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .compassEnabled(false)
        mapView = MapView(ctx, options)
        mapView.onCreate(null)
        mapPane.addView(mapView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                if (!destroyed) enableLocation(map, style)
            }
        }

        buildTurnCard()
    }

    // ---------------- Lifecycle ----------------

    fun start(url: String) {
        mapView.onStart()
        mapView.onResume()
        web.loadUrl(url)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (_: Exception) {
        }
        try {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        } catch (_: Exception) {
        }
    }

    // ---------------- Web side ----------------

    private fun setUpWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Drop the WebView marker so YouTube serves the normal mobile site.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // Block "open in app" intents (intent://, vnd.youtube://) so we stay in the pane.
                return scheme != "http" && scheme != "https"
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

    fun load(url: String) {
        if (destroyed) return
        exitFullscreen()
        web.loadUrl(url)
    }

    fun back() {
        if (destroyed) return
        if (customView != null) {
            exitFullscreen()
        } else if (web.canGoBack()) {
            web.goBack()
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

    fun scrollPage(direction: Int) {
        if (destroyed || moving || customView != null) return
        web.evaluateJavascript("window.scrollBy(0, $direction * window.innerHeight * 0.7)", null)
    }

    fun scrollWeb(dx: Float, dy: Float) {
        if (destroyed || moving || customView != null) return
        val d = ctx.resources.displayMetrics.density
        web.evaluateJavascript("window.scrollBy(${(dx / d).roundToInt()}, ${(dy / d).roundToInt()})", null)
    }

    /** A tap on the car screen becomes a real touch on whatever view is under it. */
    fun tap(x: Float, y: Float) {
        if (destroyed) return
        if (moving && isInside(videoPane, x, y)) return
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

    private fun isInside(v: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
    }

    // ---------------- Driving lock ----------------

    fun setMoving(isMoving: Boolean) {
        moving = isMoving
        movingOverlay.visibility = if (isMoving) View.VISIBLE else View.GONE
    }

    // ---------------- Insets from Android Auto ----------------

    fun applyInsets(stable: Rect, width: Int, height: Int) {
        val base = dp(8)
        row.setPadding(
            maxOf(stable.left, 0) + base,
            maxOf(stable.top, 0) + base,
            maxOf(width - stable.right, 0) + base,
            maxOf(height - stable.bottom, 0) + base,
        )
    }

    // ---------------- Map + Google Maps turn card ----------------

    @SuppressLint("MissingPermission")
    private fun enableLocation(map: MapLibreMap, style: Style) {
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            val lc = map.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(ctx, style)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.renderMode = RenderMode.GPS
            lc.zoomWhileTracking(16.0)
            lc.tiltWhileTracking(45.0)
        } catch (_: Exception) {
        }
    }

    private fun buildTurnCard() {
        turnCard.orientation = LinearLayout.VERTICAL
        turnCard.setPadding(dp(12), dp(10), dp(12), dp(10))
        turnCard.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#0F9D74"))
        }
        turnCard.visibility = View.GONE

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(turnIcon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        turnTitle.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        turnText.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
        }
        texts.addView(turnTitle)
        texts.addView(turnText)
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        turnCard.addView(top)

        mapPane.addView(turnCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(8), dp(8), dp(8), 0)
        })

        turnSub.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6202124"))
            }
            visibility = View.GONE
        }
        mapPane.addView(turnSub, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(8), 0, dp(8), dp(28))
        })

        noNavHint.apply {
            text = "Start a route in Google Maps on your phone to see turns here"
            setTextColor(Color.parseColor("#DDE3E8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#CC202124"))
            }
        }
        mapPane.addView(noNavHint, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(8), dp(8), dp(8), 0)
        })
    }

    fun setNav(info: NavInfo?) {
        if (destroyed) return
        if (info == null) {
            turnCard.visibility = View.GONE
            turnSub.visibility = View.GONE
            noNavHint.visibility = View.VISIBLE
            return
        }
        noNavHint.visibility = View.GONE
        turnCard.visibility = View.VISIBLE
        turnTitle.text = info.title ?: ""
        turnText.text = info.text ?: ""
        turnText.visibility = if (info.text.isNullOrBlank()) View.GONE else View.VISIBLE
        if (info.icon != null) {
            turnIcon.setImageDrawable(info.icon)
            turnIcon.visibility = View.VISIBLE
        } else {
            turnIcon.visibility = View.GONE
        }
        if (info.sub.isNullOrBlank()) {
            turnSub.visibility = View.GONE
        } else {
            turnSub.text = info.sub
            turnSub.visibility = View.VISIBLE
        }
    }

    // ---------------- Helpers ----------------

    private fun rounded(v: View, color: String) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor(color))
        }
        v.outlineProvider = ViewOutlineProvider.BACKGROUND
        v.clipToOutline = true
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        // Free OpenStreetMap vector tiles, no API key needed.
        const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
    }
}
