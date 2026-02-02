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
import kotlin.random.Random
import kotlin.random.nextInt
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.farmerassistant.app.ui.home.fragments.FarmField
import android.app.PendingIntent
import android.content.Intent
import com.farmerassistant.app.ui.home.HomeActivity

data class SoilHealth(
    val nitrogen: Int,
    val phosphorus: Int,
    val potassium: Int,
    val status: String,
    val recommendation: String,
    val irrigationAdvice: String,
    val farmName: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Worker class to run daily soil health computation based on user location and climate conditions.
 * It iterates through all defined plots.
 */
class SoilAnalysisWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient()
    private val apiKey = "c4fcb8a7c355e31838a382beae58bde6" // OpenWeatherMap API Key

    companion object {
        const val WORK_TAG = "SoilAnalysisWork"
        const val SOIL_COLLECTION = "soil_health"
        private const val NOTIFICATION_CHANNEL_ID = "soil_analysis_channel"
        private const val NOTIFICATION_ID_BASE = 1001
    }

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.failure()
        Log.d(WORK_TAG, "Starting multi-plot soil analysis for UID: $uid")

        try {
            // 1. Fetch ALL plot data
            val plots = loadAllFarmPlots(uid)

            if (plots.isEmpty()) {
                Log.w(WORK_TAG, "No farm plots found. Analysis skipped.")
                return Result.success()
            }

            val results = mutableListOf<SoilHealth>()

            // 2. Iterate and analyze each plot
            for (plot in plots) {
                if (plot.lat == 0.0 && plot.lng == 0.0) {
                    Log.w(WORK_TAG, "Plot ${plot.name} has no coordinates. Skipping.")
                    continue
                }
                Log.d(WORK_TAG, "Analyzing plot: ${plot.name} (Crop: ${plot.crop})")

                // Fetch current weather data for the plot's location
                val weatherUrl =
                    "https://api.openweathermap.org/data/2.5/weather?lat=${plot.lat}&lon=${plot.lng}&appid=$apiKey&units=metric"
                val weatherData = fetchWeatherData(weatherUrl)
                val currentTemp = weatherData.optJSONObject("main")?.optDouble("temp") ?: 25.0
                val conditionId =
                    weatherData.optJSONArray("weather")?.optJSONObject(0)?.optInt("id") ?: 800
                val precipRecent =
                    weatherData.optJSONObject("rain")?.optDouble("1h") ?: 0.0 // 1-hour rainfall

                // 🔥 Perform prediction tailored to the plot's crop and name
                val soilHealth = predictSoilHealth(
                    plot.lat,
                    plot.lng,
                    currentTemp,
                    conditionId,
                    precipRecent, // 🔥 Pass precipitation
                    plot.crop,
                    plot.name
                )

                results.add(soilHealth)

                // 3. Save results to Firestore under a unique document: UID_PLOTID
                val soilData = hashMapOf(
                    "uid" to uid,
                    "farm_id" to plot.id,
                    "farm_name" to soilHealth.farmName,
                    "crop" to plot.crop,
                    "lat" to plot.lat,
                    "lng" to plot.lng,
                    "nitrogen" to soilHealth.nitrogen,
                    "phosphorus" to soilHealth.phosphorus,
                    "potassium" to soilHealth.potassium,
                    "status" to soilHealth.status,
                    "recommendation" to soilHealth.recommendation,
                    "irrigationAdvice" to soilHealth.irrigationAdvice, // 🔥 PHASE 16: Save new advice
                    "timestamp" to soilHealth.timestamp
                )

                // Save to a document named after the UID and PLOT ID, e.g., 'soil_health/user123_plot1'
                db.collection(SOIL_COLLECTION).document("${uid}_${plot.id}").set(soilData).await()
            }

            // 4. Show summary notification
            showNotification(plots, results)

            Log.d(WORK_TAG, "Soil analysis completed for ${plots.size} plots.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Soil analysis failed: ${e.message}", e)
            return Result.retry()
        }
    }

    // --- loadAllFarmPlots (UNCHANGED) ---
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

            // Plot Reconstruction Logic (Robust from fragments)
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
    // --- END loadAllFarmPlots ---


    /**
     * Helper function to fetch weather data from OpenWeatherMap.
     */
    private suspend fun fetchWeatherData(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Weather API call failed.")
                JSONObject(response.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.e(WORK_TAG, "Weather fetch failed: ${e.message}")
            JSONObject() // Return empty on failure
        }
    }

    /**
     * Climate-aware simulation of nutrient levels and recommendations.
     * 🔥 PHASE 16: Updated to include precipitation (for moisture) and irrigation advice.
     */
    private fun predictSoilHealth(
        lat: Double,
        lng: Double,
        temp: Double,
        conditionId: Int,
        precipRecent: Double,
        crops: String,
        farmName: String
    ): SoilHealth {
        // Use location and current day as seed for a deterministic, yet changing, result
        val seed =
            ((lat.toInt() % 100) + (lng.toInt() % 100) + (System.currentTimeMillis() / (1000 * 60 * 60 * 24))).toLong()
        val random = Random(seed)

        // Baseline NPK values
        var n = random.nextInt(40..150)
        var p = random.nextInt(10..60)
        var k = random.nextInt(100..300)

        var status = "Optimal"
        var recommendation = "Soil health is good. Maintain routine fertilizer schedule."
        var irrigationAdvice =
            "No immediate irrigation needed. Monitor soil." // 🔥 PHASE 16: Default advice

        // --- 1. CLIMATE & WATER MANAGEMENT IMPACT (Phase 16) ---

        // Crop Water Requirements (Simplified)
        val isWaterIntensive = crops.contains("Rice", ignoreCase = true) || crops.contains(
            "Sugarcane",
            ignoreCase = true
        )
        val isDroughtTolerant =
            crops.contains("Wheat", ignoreCase = true) || crops.contains("Maize", ignoreCase = true)

        // Heavy Rain/Flood condition (Condition IDs 200-531, 600-622)
        if (conditionId in 200..531 || conditionId in 600..622 || precipRecent >= 5.0) {
            n = (n * 0.85).toInt().coerceAtLeast(30) // Nitrogen leaching
            recommendation =
                "Heavy rain/flooding detected. Nitrogen may be low due to leaching. Check soil moisture before irrigating."
            status = "Leaching Risk"
            // ALERT FOR OVERWATERING
            irrigationAdvice =
                "💧 STOP IRRIGATION! Significant rain (${precipRecent}mm) or flood risk detected. Ensure drainage to prevent waterlogging." // Alerts for overwatering
        } else if (temp > 35.0) {
            p = (p * 0.90).toInt().coerceAtLeast(10) // Phosphorus uptake inhibition
            recommendation =
                "High temperatures detected. Phosphorus uptake might be inhibited. Ensure soil moisture is adequate."
            status = "Heat Stress Risk"
            // ALERT FOR UNDERWATERING/HEAT
            irrigationAdvice =
                "🥵 IMMEDIATE IRRIGATION REQUIRED. Severe heat stress. Apply water in the early morning or late evening."
        }

        // General Irrigation Needs if not Raining/Extreme Heat
        else if (precipRecent < 0.5) { // If no significant rain recently
            // Logic: Water-intensive crops need more frequent irrigation
            if (isWaterIntensive) {
                irrigationAdvice =
                    "⏰ SCHEDULE IRRIGATION TODAY. Water-intensive crop requires immediate action due to dry weather."
            } else if (!isDroughtTolerant && temp > 28.0) {
                irrigationAdvice =
                    "⚠️ PREDICTIVE: Irrigate within 24 hours. Soil moisture likely dropping due to high temperatures."
            }
        } else {
            // Recent rain, but temperature is mild
            irrigationAdvice =
                "✅ No irrigation needed. Recent rain (${precipRecent}mm) provides adequate moisture. Monitor soil."
        }


        // --- 2. NPK CHECK (Nutrient Advice) ---

        when {
            status.contains("Risk") -> { /* Use specialized recommendation */
            }

            n < 50 -> {
                status = "Nitrogen Deficient"
                recommendation =
                    "Apply urea or organic nitrogen-rich manure immediately. Target N-P-K ratio adjustment."
            }

            p < 20 -> {
                status = "Phosphorus Low"
                recommendation =
                    "Use DAP (Diammonium Phosphate) or single super phosphate. Essential for root growth."
            }

            k < 120 -> {
                status = "Potassium Low"
                recommendation =
                    "Apply MOP (Muriate of Potash). Crucial for disease resistance and fruit quality."
            }

            else -> {
                if (n > 120 || k > 250) {
                    status = "Nutrient Excess"
                    recommendation =
                        "Reduce fertilizer application for the next cycle to prevent nutrient burn and runoff."
                } else if (status != "Optimal") {
                    // If status was already set to a risk, keep the recommendation
                } else {
                    status = "Optimal"
                    recommendation =
                        "Soil health is optimal. Continue routine activities and monitor weather."
                }
            }
        }

        // 🔥 Return the updated SoilHealth object including farmName and irrigation advice
        return SoilHealth(n, p, k, status, recommendation, irrigationAdvice, farmName)
    }

    // File: SoilAnalysisWorker.kt

// ... (existing code, ensure all imports are present)

    private fun showNotification(plots: List<FarmField>, results: List<SoilHealth>) {
        // --- START FIX 2: Runtime Permission Check ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(WORK_TAG, "Notification permission denied. Cannot show soil update.")
            return // Stop execution if permission is denied
        }
        // --- END FIX 2 ---

        // --- START FIX 1: Declare pendingIntent before assignment ---
        val notificationId = NOTIFICATION_ID_BASE
        lateinit var pendingIntent: PendingIntent
        // --- END FIX 1 ---

        val summaryTitle = "Soil & Water Update (${plots.size} Plots)"

        val criticalAlertPlots = plots.zip(results).filter { (_, result) ->
            result.irrigationAdvice.contains("IMMEDIATE", ignoreCase = true) ||
                    result.irrigationAdvice.contains("STOP IRRIGATION", ignoreCase = true) ||
                    result.status.contains("Deficient") || result.status.contains("Low") || result.status.contains("Risk")
        }

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(summaryTitle)
            .setSummaryText("Review essential alerts for your farms.")

        var summaryMessage = "All plots are healthy."
        var highPriority = NotificationCompat.PRIORITY_DEFAULT

        if (criticalAlertPlots.isNotEmpty()) {
            inboxStyle.addLine("⚠️ **CRITICAL ALERTS** for ${criticalAlertPlots.size} plots.")
            criticalAlertPlots.take(5).forEach { (plot, result) ->
                val alertType = if (result.irrigationAdvice.contains("IMMEDIATE") || result.irrigationAdvice.contains("STOP")) "💧 Water Action" else "🧪 Nutrient Risk"
                inboxStyle.addLine("Plot ${plot.name}: $alertType (${result.status})")
            }
            summaryMessage = "Action required for ${criticalAlertPlots.size} plots. Check the Climate Dashboard."
            highPriority = NotificationCompat.PRIORITY_HIGH
        } else {
            inboxStyle.addLine("✅ All ${plots.size} plots are currently Optimal.")
        }

        createNotificationChannel()

        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            putExtra(HomeActivity.NOTIFICATION_TARGET_FRAGMENT_KEY, HomeActivity.FRAGMENT_CLIMATE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Assign the PendingIntent (This must happen after the intent is defined)
        pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(summaryTitle)
            .setContentText(summaryMessage)
            .setStyle(inboxStyle)
            .setPriority(highPriority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(notificationId, builder.build())
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Soil Health Updates"
            val descriptionText = "Daily reports and alerts regarding farm soil nutrient levels."
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}