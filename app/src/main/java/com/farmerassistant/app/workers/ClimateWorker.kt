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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.farmerassistant.app.ui.home.fragments.FarmField
import android.app.PendingIntent
import android.content.Intent
import com.farmerassistant.app.ui.home.HomeActivity

class ClimateWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient()
    private val apiKey = "c4fcb8a7c355e31838a382beae58bde6"

    companion object {
        const val WORK_TAG = "ClimateUpdateWork"
        const val CLIMATE_COLLECTION = "climate_data"
        private const val NOTIFICATION_CHANNEL_ID = "climate_update_channel"
        private const val NOTIFICATION_ID = 1002
    }

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.failure()
        Log.d(WORK_TAG, "Starting multi-plot climate update for UID: $uid")

        try {

            val plots = loadAllFarmPlots(uid)

            if (plots.isEmpty()) {
                Log.w(WORK_TAG, "No farm plots found. Climate update skipped.")
                return Result.success()
            }

            var successCount = 0

            for (plot in plots) {
                if (plot.lat == 0.0 && plot.lng == 0.0) {
                    Log.w(WORK_TAG, "Plot ${plot.name} has no coordinates. Skipping.")
                    continue
                }

                val weatherUrl =
                    "https://api.openweathermap.org/data/2.5/weather?lat=${plot.lat}&lon=${plot.lng}&appid=$apiKey&units=metric"
                val currentWeatherJson = fetchWeatherData(weatherUrl)

                if (currentWeatherJson.isNotEmpty()) {

                    saveClimateDataToFirestore(currentWeatherJson, uid, plot.id)
                    successCount++
                }
            }


            if (successCount > 0) {
                val primaryPlotSummary = fetchSummaryDataForNotification(uid, plots.first().id)
                showNotification(primaryPlotSummary, plots.size)
            }


            Log.d(WORK_TAG, "Climate update completed. Saved data for $successCount plots.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Climate update failed: ${e.message}", e)
            return Result.retry()
        }
    }

    /**
     * Fetches weather data and ensures success before returning the JSON string.
     */
    private suspend fun fetchWeatherData(url: String): String {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty() || JSONObject(body).optString("cod") != "200") {
                    throw IOException("Weather API call failed or returned error: ${response.code}.")
                }
                body
            }
        } catch (e: Exception) {
            Log.e(WORK_TAG, "Weather fetch failed: ${e.message}")
            ""
        }
    }

    /**
     * Saves critical weather metrics to Firestore using UID_PLOTID.
     */
    private suspend fun saveClimateDataToFirestore(
        currentWeatherJson: String,
        uid: String,
        plotId: String
    ) {
        val json = JSONObject(currentWeatherJson)

        val main = json.optJSONObject("main")
        val wind = json.optJSONObject("wind")
        val clouds = json.optJSONObject("clouds")
        val rain = json.optJSONObject("rain")

        val tempAvg = main?.optDouble("temp") ?: 0.0
        val humidity = main?.optInt("humidity") ?: 0
        val windSpeed = wind?.optDouble("speed") ?: 0.0
        val precipRecent = (rain?.optDouble("1h") ?: 0.0)
        val weather = json.optJSONArray("weather")?.optJSONObject(0)


        val dataToSave = hashMapOf<String, Any>(
            "temp_avg" to tempAvg,
            "humidity" to humidity,
            "pressure" to (main?.optInt("pressure") ?: 0),
            "wind_speed" to windSpeed,
            "cloudiness" to (clouds?.optInt("all") ?: 0),
            "precip_24h" to precipRecent,
            "timestamp" to System.currentTimeMillis()
        )

        try {
            // 🔥 CRITICAL FIX: Save data to UID_PLOTID document
            db.collection(CLIMATE_COLLECTION).document("${uid}_${plotId}").set(dataToSave).await()
            Log.d(WORK_TAG, "Climate data saved for plot $plotId.")

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Failed to save climate data for $plotId: ${e.message}")
        }
    }

    /**
     * Fetches the climate summary for the notification message (using the primary plot).
     */
    private suspend fun fetchSummaryDataForNotification(
        uid: String,
        primaryPlotId: String
    ): Map<String, String> {
        val doc =
            db.collection(CLIMATE_COLLECTION).document("${uid}_${primaryPlotId}").get().await()
        if (!doc.exists()) return emptyMap()

        val weatherSummary = mutableMapOf<String, String>()

        // Fetch original weather condition from the document if available
        // NOTE: This usually requires a separate fetch or storing the description,
        // but for simplicity, we rely on the saved fields.

        weatherSummary["temp"] = "%.0f°C".format(doc.getDouble("temp_avg") ?: 0.0)

        // As we don't save the condition description, let's use a placeholder description based on temp/rain
        val temp = doc.getDouble("temp_avg") ?: 0.0
        val precip = doc.getDouble("precip_24h") ?: 0.0
        val condition = when {
            precip > 1.0 -> "Rainy"
            temp > 35.0 -> "Hot & Clear"
            else -> "Mostly Clear"
        }

        weatherSummary["condition"] = condition
        weatherSummary["wind"] = "%.1f m/s".format(doc.getDouble("wind_speed") ?: 0.0)
        weatherSummary["humidity"] = "${doc.getLong("humidity") ?: 0}%"

        return weatherSummary
    }

    /**
     * Placeholder for the loadAllFarmPlots function (Copied from SoilWorker for completeness).
     */
    private suspend fun loadAllFarmPlots(uid: String): List<FarmField> {
        val currentFields = mutableListOf<FarmField>()
        try {
            val userDoc = db.collection("users").document(uid).get().await()

            @Suppress("UNCHECKED_CAST")
            val farmData = userDoc.get("farm_data") as? Map<String, Any>

            val plotNames = farmData?.get("plots_names") as? List<String> ?: emptyList()
            val plotsCrops = farmData?.get("plots_crops") as? List<String> ?: emptyList()
            val coordsMapList =
                farmData?.get("plots_coordinates_flat") as? List<Map<String, Any>> ?: emptyList()

            val primaryLat = userDoc.getDouble("lat") ?: 0.0
            val primaryLng = userDoc.getDouble("lng") ?: 0.0
            val primaryCrop =
                userDoc.getString("crops_grown")?.split(",")?.firstOrNull()?.trim() ?: "Paddy"

            var currentPlotCoords = mutableListOf<Map<String, Any>>()
            var attributeIndex = 0

            // Plot Reconstruction Logic
            for (coordMap in coordsMapList) {
                val isSeparator = coordMap["separator"] == true

                if (isSeparator) {
                    if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                        val anchorPoint = currentPlotCoords.first()
                        val name =
                            plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                        val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                        currentFields.add(
                            FarmField(
                                id = name.replace(" ", "_"),
                                name = name,
                                crop = crop,
                                lat = anchorPoint["latitude"] as? Double ?: 0.0,
                                lng = anchorPoint["longitude"] as? Double ?: 0.0
                            )
                        )
                        attributeIndex++
                    }
                    currentPlotCoords = mutableListOf()
                } else {
                    currentPlotCoords.add(coordMap)
                }
            }
            if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                val anchorPoint = currentPlotCoords.first()
                val name = plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                currentFields.add(
                    FarmField(
                        id = name.replace(" ", "_"),
                        name = name,
                        crop = crop,
                        lat = anchorPoint["latitude"] as? Double ?: 0.0,
                        lng = anchorPoint["longitude"] as? Double ?: 0.0
                    )
                )
            }

            // Fallback for single anchor
            if (currentFields.isEmpty() && primaryLat != 0.0) {
                currentFields.add(
                    FarmField(
                        "primary",
                        "Primary Farm Location",
                        primaryCrop,
                        primaryLat,
                        primaryLng
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Failed to load farm list from Firestore: ${e.message}", e)
        }
        return currentFields
    }


    private fun showNotification(weatherSummary: Map<String, String>, plotCount: Int) {
        if (weatherSummary.isEmpty()) return

        // Runtime Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(WORK_TAG, "Notification permission denied. Cannot show climate update.")
            return
        }

        val title = "🌤️ Farm Weather Update (${plotCount} Plots)"
        val message = "Current: ${weatherSummary["condition"]} | ${weatherSummary["temp"]}"

        createNotificationChannel()

        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            // Add the extra data to tell HomeActivity where to redirect
            putExtra(HomeActivity.NOTIFICATION_TARGET_FRAGMENT_KEY, HomeActivity.FRAGMENT_CLIMATE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // 2. Create the PendingIntent
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // --- END REDIRECTION LOGIC ---

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Temp: ${weatherSummary["temp"]}, Humid: ${weatherSummary["humidity"]}, Wind: ${weatherSummary["wind"]}")
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent) // 🔥 Attach the PendingIntent
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(NOTIFICATION_ID, builder.build())
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Climate Updates"
            val descriptionText = "Periodic weather reports for the farm area."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}