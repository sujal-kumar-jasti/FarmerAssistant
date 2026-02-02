package com.farmerassistant.app.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.farmerassistant.app.R
import com.farmerassistant.app.ui.home.HomeActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.atomic.AtomicInteger

class FCMService : FirebaseMessagingService() {

    private val NOTIFICATION_CHANNEL_ID = "farmer_assistant_alerts"
    private val NOTIFICATION_CHANNEL_NAME = "Farm Alerts"
    private val NOTIFICATION_ID = AtomicInteger(0)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        if (remoteMessage.notification != null) {
            val title = remoteMessage.notification?.title ?: "Farm Alert"
            val body = remoteMessage.notification?.body ?: "New update available."
            val tag = remoteMessage.data["tag"] ?: "default"

            sendNotification(title, body, tag)
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCMService", "Refreshed token: $token")
        // In a production app, save this token to the user's Firestore document for targeted sending.
        // Example: saveTokenToFirestore(token)
    }

    private fun sendNotification(title: String, body: String, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("FCMService", "POST_NOTIFICATIONS permission not granted. Cannot display notification.")
                return
            }
        }

        val intent = Intent(this, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Use the tag to navigate to a specific fragment if needed (e.g., "weather", "market")
            putExtra("notification_tag", tag)
        }

        // Use a unique ID for the notification for potential grouping/replacement
        val pendingIntent = PendingIntent.getActivity(
            this, NOTIFICATION_ID.incrementAndGet(), intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // NOTE: Requires an ic_notification.xml file
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID.get(), notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for farm management (Weather, Soil, Market)."
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
