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
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.farmerassistant.app.ui.home.HomeActivity
import com.farmerassistant.app.ui.home.fragments.MandiPriceData
import com.farmerassistant.app.ui.home.fragments.MarketFragment // Access constants/methods from MarketFragment
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.content.Intent
import org.json.JSONObject

class MarketAlertWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val gson = Gson()
    private val TAG = "MarketAlertWorker"

    companion object {
        const val WORK_TAG = "MarketAlertWork"
        private const val NOTIFICATION_CHANNEL_ID = "market_alert_channel"
        private const val NOTIFICATION_ID = 1005
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting market price swing check.")

        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val currentJson = prefs.getString("cached_weather", null)
            val previousDayJson = prefs.getString("previous_day_market_data", null)

            if (currentJson == null || previousDayJson == null) {
                Log.w(TAG, "Market data (current or previous) missing in cache. Skipping check.")
                return Result.success()
            }

            // Parse data using MarketFragment's parsing logic (assuming accessibility)
            val currentPrices = parseMandiJson(currentJson).second
            val previousPrices = parseMandiJson(previousDayJson).second

            // Simplified: Find a significant drop (e.g., > 5%) in the modal price
            val dropAlerts = mutableListOf<String>()

            currentPrices.forEach { (cropLabel, current) ->
                val prev = previousPrices[cropLabel]

                if (prev != null && prev.modalPrice > 0) {
                    val dropPercentage = 100.0 * (prev.modalPrice - current.modalPrice) / prev.modalPrice

                    if (dropPercentage >= 5.0) {
                        dropAlerts.add("${cropLabel.split(" ").first()} price dropped ${String.format("%.1f", dropPercentage)}% (₹${current.modalPrice}/Qtl).")
                    }
                }
            }

            if (dropAlerts.isNotEmpty()) {
                showNotification(dropAlerts)
            } else {
                Log.d(TAG, "No significant market price drops detected.")
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Market alert check failed: ${e.message}", e)
            return Result.failure()
        }
    }

    // NOTE: This parsing helper must be identical to the one in MarketFragment.kt
    private fun parseMandiJson(jsonString: String): Pair<String, Map<String, MandiPriceData>> {
        val dataMap = mutableMapOf<String, MandiPriceData>()
        var marketDate = "N/A"
        try {
            val json = JSONObject(jsonString)

            marketDate = json.optString("updated_date", "N/A").split(" ").firstOrNull() ?: "N/A"

            if (!json.has("records") || json.optInt("count", 0) == 0) {
                return Pair(marketDate, emptyMap())
            }

            val records = json.getJSONArray("records")
            val addedCommodities = mutableSetOf<String>()

            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val commodity = record.optString("commodity", "Unknown").trim()
                val market = record.optString("market", "").trim()

                val modalPrice = record.optString("modal_price", "0").toFloatOrNull()?.toInt() ?: 0
                val minPrice = record.optString("min_price", "0").toFloatOrNull()?.toInt() ?: 0
                val maxPriceString = record.optString("max_price", "0")
                val parsedMaxPrice = maxPriceString.toFloatOrNull()?.toInt() ?: 0

                val maxPrice = maxOf(parsedMaxPrice, modalPrice)

                val simpleCropName = commodity.split(" ").first()

                if (modalPrice > 0 && commodity.isNotEmpty() && !addedCommodities.contains(simpleCropName)) {
                    val priceData = MandiPriceData(modalPrice, minPrice, maxPrice)
                    dataMap["$commodity ($market)"] = priceData
                    addedCommodities.add(simpleCropName)
                }
            }
            return Pair(marketDate, dataMap)
        } catch (e: Exception) {
            Log.e(TAG, "JSON Parsing Error: ${e.message}")
            return Pair(marketDate, emptyMap())
        }
    }


    private fun showNotification(alerts: List<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission denied. Cannot show market alert.")
            return
        }

        val title = "📉 Market Alert: Price Drop Detected"
        val message = alerts.first()

        createNotificationChannel()

        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            putExtra(HomeActivity.NOTIFICATION_TARGET_FRAGMENT_KEY, HomeActivity.FRAGMENT_ALERTS) // Redirect to the Market Fragment
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText("Check the Market Prices section now.")

        alerts.forEach { alert ->
            inboxStyle.addLine(alert)
        }

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Market Price Alerts"
            val descriptionText = "Alerts for significant drops or swings in Mandi prices."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}