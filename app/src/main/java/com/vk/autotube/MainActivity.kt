package com.vk.autotube

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Phone side: search, paste or share a video, and it plays on the car screen. */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var recentsBox: LinearLayout
    private lateinit var permCard: View

    private val libraryListener: () -> Unit = { renderRecents() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.init(this)
        window.decorView.setBackgroundColor(Palette.BG)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        setContentView(buildUi())
        Library.addListener(libraryListener)
        renderRecents()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onDestroy() {
        Library.removeListener(libraryListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermission()
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        dispatch(Bridge.parse(text))
        // Go straight back to YouTube after sharing.
        finish()
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isBlank()) return
        dispatch(Bridge.parse(text))
        input.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun pasteLink() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) {
            toast("Clipboard is empty")
        } else {
            dispatch(Bridge.parse(text))
        }
    }

    private fun dispatch(cmd: CarCommand) {
        if (cmd is CarCommand.Play) Library.add(this, cmd.videoId)
        Bridge.send(cmd)
        val msg = when (cmd) {
            is CarCommand.Play -> "Playing on your car"
            is CarCommand.Browse -> "Opened on your car"
            is CarCommand.Home -> "Car screen is back home"
        }
        toast(msg)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermission() {
        if (::permCard.isInitialized) permCard.visibility = if (hasLocation()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildUi(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(56), dp(20), dp(48))

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_logo)
        val logoLp = LinearLayout.LayoutParams(dp(46), dp(46))
        logoLp.marginEnd = dp(14)
        header.addView(logo, logoLp)
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(label("AutoTube", 28f, Palette.TEXT, 800))
        titles.addView(label("YouTube on your car screen", 14f, Palette.MUTED))
        header.addView(titles)
        col.addView(header)

        // Location permission
        permCard = buildPermCard()
        col.addView(permCard, lp(MATCH_PARENT, WRAP_CONTENT, top = 22))

        // Search
        val search = LinearLayout(this)
        search.orientation = LinearLayout.HORIZONTAL
        search.gravity = Gravity.CENTER_VERTICAL
        search.background = rounded(Palette.SURFACE, dpf(30f))
        search.setPadding(dp(18), dp(6), dp(6), dp(6))

        val searchIcon = ImageView(this)
        searchIcon.setImageResource(R.drawable.ic_search)
        searchIcon.alpha = 0.7f
        search.addView(searchIcon, LinearLayout.LayoutParams(dp(22), dp(22)))

        input = EditText(this)
        input.background = null
        input.hint = "Search YouTube or paste a link"
        input.setHintTextColor(Palette.MUTED)
        input.setTextColor(Palette.TEXT)
        input.textSize = 16f
        input.setSingleLine(true)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setPadding(dp(12), dp(12), dp(8), dp(12))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        search.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val go = ImageView(this)
        go.setImageResource(R.drawable.ic_play)
        go.background = pressable(circle(Palette.ACCENT))
        val pad = dp(11)
        go.setPadding(pad, pad, pad, pad)
        go.isClickable = true
        go.setOnClickListener { submit() }
        search.addView(go, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(search, lp(MATCH_PARENT, WRAP_CONTENT, top = 24))

        // Quick actions
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        val pasteLp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        pasteLp.marginEnd = dp(10)
        chips.addView(chip(R.drawable.ic_link, "Paste link") { pasteLink() }, pasteLp)
        chips.addView(
            chip(R.drawable.ic_home, "Car home") { dispatch(CarCommand.Home) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        col.addView(chips, lp(MATCH_PARENT, WRAP_CONTENT, top = 12))

        // Recents
        col.addView(label("Recently played", 18f, Palette.TEXT, 700), lp(WRAP_CONTENT, WRAP_CONTENT, top = 34))
        recentsBox = LinearLayout(this)
        recentsBox.orientation = LinearLayout.VERTICAL
        col.addView(recentsBox, lp(MATCH_PARENT, WRAP_CONTENT, top = 8))

        col.addView(
            label("Tip: in the YouTube app, tap Share on any video and pick AutoTube.", 13f, Palette.MUTED),
            lp(MATCH_PARENT, WRAP_CONTENT, top = 26),
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Palette.BG)
        scroll.isFillViewport = true
        scroll.addView(col)
        return scroll
    }

    private fun buildPermCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = rounded(Palette.SURFACE, dpf(18f))
        card.setPadding(dp(16), dp(14), dp(12), dp(14))
        card.addView(
            label("Allow location so video can pause while you drive.", 14f, Palette.TEXT),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        val allow = label("Allow", 14f, Color.WHITE, 700)
        allow.background = pressable(rounded(Palette.ACCENT, dpf(18f)))
        allow.setPadding(dp(16), dp(9), dp(16), dp(9))
        allow.isClickable = true
        allow.setOnClickListener {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1,
            )
        }
        val allowLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        allowLp.marginStart = dp(12)
        card.addView(allow, allowLp)
        return card
    }

    private fun chip(icon: Int, text: String, onClick: () -> Unit): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER
        c.background = pressable(rounded(Palette.SURFACE, dpf(22f)))
        c.setPadding(dp(14), dp(13), dp(14), dp(13))
        c.isClickable = true
        c.setOnClickListener { onClick() }
        val iv = ImageView(this)
        iv.setImageResource(icon)
        val ivLp = LinearLayout.LayoutParams(dp(18), dp(18))
        ivLp.marginEnd = dp(8)
        c.addView(iv, ivLp)
        c.addView(label(text, 15f, Palette.TEXT, 600))
        return c
    }

    private fun renderRecents() {
        if (!::recentsBox.isInitialized) return
        recentsBox.removeAllViews()
        val items = Library.recents()
        if (items.isEmpty()) {
            recentsBox.addView(
                label("Videos you send to the car show up here.", 14f, Palette.MUTED),
                lp(MATCH_PARENT, WRAP_CONTENT, top = 6),
            )
            return
        }
        for (v in items) recentsBox.addView(recentRow(v), lp(MATCH_PARENT, WRAP_CONTENT, top = 4))
    }

    private fun recentRow(v: Video): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = pressable(null, rounded(Color.WHITE, dpf(16f)))
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        row.isClickable = true
        row.setOnClickListener { dispatch(CarCommand.Play(v.id)) }

        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = rounded(Palette.SURFACE, dpf(12f))
        thumb.clipToRounded()
        thumb.tag = v.id
        row.addView(thumb, LinearLayout.LayoutParams(dp(128), dp(72)))
        Library.thumbnail(v.id) { bmp ->
            if (bmp != null && thumb.tag == v.id) thumb.setImageBitmap(bmp)
        }

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(14), 0, dp(4), 0)
        val title = label(v.title.ifEmpty { "Loading title…" }, 15f, Palette.TEXT, 600)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title)
        if (v.channel.isNotEmpty()) {
            val channel = label(v.channel, 13f, Palette.MUTED)
            channel.maxLines = 1
            channel.ellipsize = TextUtils.TruncateAt.END
            texts.addView(channel, lp(WRAP_CONTENT, WRAP_CONTENT, top = 3))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun lp(w: Int, h: Int, top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top)
        return p
    }
}
