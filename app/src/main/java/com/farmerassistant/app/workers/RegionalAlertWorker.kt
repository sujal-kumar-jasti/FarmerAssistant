package com.farmerassistant.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.farmerassistant.app.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Date
import android.app.PendingIntent
import android.content.Intent
import com.farmerassistant.app.ui.home.HomeActivity
class RegionalAlertWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    companion object {
        const val WORK_TAG = "RegionalAlertWork"
        const val REGIONAL_REPORTS_COLLECTION = "regional_disease_reports"
        private const val NOTIFICATION_CHANNEL_ID = "regional_alert_channel"
        private const val NOTIFICATION_ID = 1004
        // Regional alert TTL (Time to Live): Reports expire after 48 hours
        private val ALERT_TTL_HOURS = 48L
    }

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.failure()
        Log.d(WORK_TAG, "Starting regional alert check for UID: $uid")

        try {
            // 1. Get user's region data (simulated by reading cached location)
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Use user_district which is cached by ClimateFragment
            val userDistrict = prefs.getString("user_district", "Unknown")

            if (userDistrict == "Unknown") {
                Log.w(WORK_TAG, "User district not cached. Skipping regional check.")
                // If context is missing, still success, but no check ran
                return Result.success()
            }

            val cutoffTime = Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(ALERT_TTL_HOURS))

            // 2. Query Firestore for recent outbreaks in the user's district
            val regionalReportsQuery = db.collection(REGIONAL_REPORTS_COLLECTION)
                .whereEqualTo("district", userDistrict)
                .whereGreaterThan("timestamp", cutoffTime.time)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(5)
                .get().await()

            var newAlertCount = 0

            // 3. Aggregate new alerts and save to the user's document for UI display
            val userAlerts = mutableListOf<Map<String, Any>>()
            regionalReportsQuery.documents.forEach { doc ->
                // Ensure we don't report the user's own report back to them
                if (doc.getString("reporterUid") != uid) {
                    userAlerts.add(mapOf(
                        "lat" to doc.getDouble("lat")!!,
                        "lng" to doc.getDouble("lng")!!,
                        "type" to "Regional Outbreak: ${doc.getString("crop")}",
                        "message" to "Reported: ${doc.getString("disease")}",
                        "severity" to doc.getString("severity")!!
                    ))
                    newAlertCount++
                }
            }

            // 4. Update the user's alerts collection (FIXED: Use SetOptions.merge())
            if (userAlerts.isNotEmpty()) {
                // IMPORTANT FIX: Use SetOptions.merge() to ensure we ONLY overwrite 'regional_outbreaks'
                // and do not delete any other fields (like 'soil_alerts' or 'weather_alerts') in the document.
                db.collection("alerts").document(uid).set(mapOf(
                    "regional_outbreaks" to userAlerts,
                    "last_updated_regional" to System.currentTimeMillis() // Added specific timestamp for regional alerts
                ), SetOptions.merge()).await()

                // 5. Notify the user if new regional alerts were found
                if (newAlertCount > 0) {
                    showNotification(newAlertCount, userDistrict)
                }
            }

            Log.d(WORK_TAG, "Regional alert check finished. Found $newAlertCount new regional alerts.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Regional alert check failed: ${e.message}", e)
            return Result.retry()
        }
    }

    private fun showNotification(count: Int, district: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(WORK_TAG, "Notification permission denied. Cannot show regional alert.")
            return
        }

        val title = "🚨 $count New Outbreaks in $district"
        val message = "Check your map for recent Pest & Disease reports from nearby farms. Act early!"

        createNotificationChannel()


        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            putExtra(HomeActivity.NOTIFICATION_TARGET_FRAGMENT_KEY, HomeActivity.FRAGMENT_DISEASE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )


        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Regional Outbreaks"
            val descriptionText = "Proactive alerts about pest and disease outbreaks in your local farming region."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}