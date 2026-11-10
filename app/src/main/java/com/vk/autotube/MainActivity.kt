package com.vk.autotube

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlin.math.roundToInt

/** Phone-side remote: permissions, search, and "share to car". */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Bridge.init(this)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        send(text)
    }

    private fun send(text: String) {
        val url = Bridge.youtubeUrlFrom(text)
        Bridge.open(this, url)
        Toast.makeText(this, "Sent to car", Toast.LENGTH_SHORT).show()
    }

    private fun hasLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val loc = if (hasLocation()) "On" else "Off (needed for the map and the driving lock)"
        val turns = if (hasNotificationAccess()) "On" else "Off (needed to mirror Google Maps turns)"
        status.text = "Location: $loc\nGoogle Maps turn info: $turns"
    }

    private fun buildUi(): ScrollView {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(56), pad, pad)
        }

        col.addView(text("AutoTube", 28f, bold = true))
        col.addView(text("YouTube next to your navigation on Android Auto. Video hides itself while the car is moving.", 15f, color = "#9AA4AE"))
        col.addView(space(16))

        status = text("", 15f, color = "#DDE3E8")
        col.addView(status)
        col.addView(space(12))

        col.addView(button("1. Allow location") {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
            )
        })
        col.addView(button("2. Allow Google Maps turn info") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(space(20))

        input = EditText(this).apply {
            hint = "Search YouTube or paste a link"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7A848E"))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    send(text.toString()); true
                } else false
            }
        }
        col.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(button("Play on car") { send(input.text.toString()) })
        col.addView(button("Paste link from clipboard") {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip.isNullOrBlank()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                send(clip)
            }
        })
        col.addView(button("YouTube home on car") { Bridge.open(this, Bridge.HOME) })
        col.addView(space(24))

        col.addView(text("Tip: in the YouTube app, tap Share on any video and pick AutoTube.", 14f, color = "#9AA4AE"))
        col.addView(space(16))
        col.addView(text("Car setup (once)", 17f, bold = true))
        col.addView(
            text(
                "1. Android Auto settings: tap Version about 10 times to unlock developer mode.\n" +
                    "2. Three-dot menu, Developer settings, turn on Unknown sources.\n" +
                    "3. Reconnect to the car. AutoTube appears in the app launcher.\n" +
                    "4. Start your route in Google Maps first, then open AutoTube on the car screen.\n" +
                    "5. Car buttons: Back and Home control the video side. Arrows scroll the page.",
                14f, color = "#B8C2CC"
            )
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101418"))
            addView(col)
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: String = "#FFFFFF") =
        TextView(this).apply {
            text = s
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }

    private fun space(h: Int) = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
