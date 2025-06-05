package com.example.untitled

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class NotiListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotiListener"
        const val VIBRATION_ACTION = "com.example.untitled.BLUETOOTH_VIBRATE_ACTION"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Notification listener service created")
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        val packageName = statusBarNotification.packageName
        val extras = statusBarNotification.notification.extras
        val notificationText = extras?.getCharSequence("android.text")?.toString() ?: "No text"
        val title = extras?.getCharSequence("android.title")?.toString() ?: "No title"

        Log.d(TAG, "Notification from $packageName: $title - $notificationText")

        // Vibrate for WhatsApp/Messenger (customize these package names)
        if (packageName == "com.whatsapp" || packageName == "com.facebook.orca" || packageName == "com.android.deskclock") {
            sendVibrationCommandToBluetooth()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: Handle notification removal if needed
        Log.d(TAG, "Notification removed from ${sbn.packageName}")
    }

    private fun sendVibrationCommandToBluetooth() {
        // Tell MainActivity to trigger vibration via Bluetooth
        val intent = Intent(VIBRATION_ACTION)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Sent vibration command broadcast")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}