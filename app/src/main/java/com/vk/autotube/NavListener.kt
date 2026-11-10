package com.vk.autotube

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads Google Maps' turn-by-turn notification (distance, street, ETA, arrow icon)
 * so the car screen can show it next to the video. Needs "Notification access".
 */
class NavListener : NotificationListenerService() {

    private var trackedKey: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == trackedKey) {
            trackedKey = null
            Bridge.postNav(null)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in MAPS_PACKAGES) return
        val n = sbn.notification
        val isNav = n.category == Notification.CATEGORY_NAVIGATION ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isNav) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        val icon = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }
        trackedKey = sbn.key
        Bridge.postNav(NavInfo(title, text, sub, icon))
    }

    companion object {
        val MAPS_PACKAGES = setOf("com.google.android.apps.maps", "com.waze")
    }
}
