package com.vk.autotube.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Decides whether the car is moving from GPS speed.
 * Moving above ~9 km/h; counts as stopped after 3 s below ~4 km/h.
 */
class MotionGate(
    private val ctx: Context,
    private val onChange: (Boolean) -> Unit,
) : LocationListener {

    var moving = false
        private set

    private var slowSince = 0L
    private var running = false
    private val lm: LocationManager? = ctx.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || lm == null) return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
            running = true
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running) return
        try {
            lm?.removeUpdates(this)
        } catch (_: Exception) {
        }
        running = false
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return
        val speed = location.speed // metres per second
        val now = SystemClock.elapsedRealtime()
        if (speed > 250f) {
            slowSince = 0L
            set(true)
        } else if (speed < 1.1f) {
            if (slowSince == 0L) slowSince = now
            if (now - slowSince > 3000L) set(false)
        }
    }

    private fun set(value: Boolean) {
        if (value != moving) {
            moving = value
            onChange(value)
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
