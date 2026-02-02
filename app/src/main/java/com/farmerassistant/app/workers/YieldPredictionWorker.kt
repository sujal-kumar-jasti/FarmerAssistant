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
import kotlin.random.Random
import java.io.IOException
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.PendingIntent
import android.content.Intent
import com.farmerassistant.app.ui.home.HomeActivity
data class YieldForecast(
    val crop: String,
    val expectedYield: Double, // in Quintals/Hectare
    val growthStage: String,
    val reductionAlert: String,
    val optimalSchedule: String,
    val farm_id: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class YieldPredictionWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    companion object {
        const val WORK_TAG = "YieldPredictionWork"
        const val YIELD_COLLECTION = "yield_predictions"
        private const val NOTIFICATION_CHANNEL_ID = "yield_prediction_channel"
        private const val NOTIFICATION_ID = 1002

        // Keys for input data from YieldPredictionFragment
        const val KEY_LAT = "input_lat"
        const val KEY_LNG = "input_lng"
        const val KEY_CROP = "input_crop"
        const val KEY_FARM_ID = "input_farm_id"
    }

    override suspend fun doWork(): Result {
        val uid = auth.currentUser?.uid ?: return Result.failure()
        Log.d(WORK_TAG, "Starting yield prediction for UID: $uid")

        try {
            // 1. Fetch input data for the specific farm/plot
            val inputCrop = inputData.getString(KEY_CROP)
            val inputLat = inputData.getDouble(KEY_LAT, 0.0)
            val inputLng = inputData.getDouble(KEY_LNG, 0.0)
            val inputFarmId = inputData.getString(KEY_FARM_ID)

            if (inputCrop.isNullOrEmpty() || inputLat == 0.0 || inputLng == 0.0 || inputFarmId.isNullOrEmpty()) {
                Log.e(WORK_TAG, "Input data (Crop, Lat, Lng, or FarmId) is missing. Cannot predict.")
                return Result.failure()
            }

            // 2. Fetch required dependent data (Soil and Climate)
            val soilDocRefId = "${uid}_${inputFarmId}"
            val climateDocRefId = "${uid}_${inputFarmId}" // Climate data is also now plot specific

            // Fetch Soil data using the composite ID (UID_FARMID)
            val soilDoc = db.collection(SoilAnalysisWorker.SOIL_COLLECTION).document(soilDocRefId).get().await()

            // Fetch Climate data using the composite ID (UID_FARMID)
            val climateDoc = db.collection(ClimateWorker.CLIMATE_COLLECTION).document(climateDocRefId).get().await()


            // 3. Extract parameters for simulation
            val n = soilDoc.getDouble("nitrogen") ?: 80.0
            val p = soilDoc.getDouble("phosphorus") ?: 30.0
            val k = soilDoc.getDouble("potassium") ?: 180.0
            val soilStatus = soilDoc.getString("status") ?: "Optimal"

            val currentTemp = climateDoc.getDouble("temp_avg") ?: 28.0
            val precipitationAvg = climateDoc.getDouble("precip_24h") ?: 0.0
            val diseaseRisk = if (soilStatus.contains("Risk")) 0.15 else 0.05

            // 4. Run prediction
            val forecast = simulateYieldPrediction(inputCrop, n, p, k, currentTemp, precipitationAvg, diseaseRisk)

            // 5. Save results to Firestore
            val yieldData = hashMapOf(
                "uid" to uid,
                "crop" to forecast.crop,
                "expectedYield" to forecast.expectedYield,
                "growthStage" to forecast.growthStage,
                "reductionAlert" to forecast.reductionAlert,
                "optimalSchedule" to forecast.optimalSchedule,
                "timestamp" to forecast.timestamp,
                "farm_lat" to inputLat,
                "farm_lng" to inputLng,
                "farm_id" to inputFarmId // Save the ID for filtering in Fragment UI
            )

            // Save yield data to a document named after the UID and FARM ID
            db.collection(YIELD_COLLECTION).document("${uid}_${inputFarmId}").set(yieldData).await()

            // 6. Show notification to the user
            showNotification(forecast, inputFarmId)

            Log.d(WORK_TAG, "Yield prediction completed and saved successfully for Plot ID: $inputFarmId.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(WORK_TAG, "Yield prediction failed: ${e.message}", e)
            return if (e is IOException) Result.retry() else Result.failure()
        }
    }

    /**
     * Simulation of a simplified predictive model based on input factors.
     */
    private fun simulateYieldPrediction(
        crop: String,
        n: Double,
        p: Double,
        k: Double,
        temp: Double,
        precip: Double,
        risk: Double
    ): YieldForecast {
        val random = Random(System.currentTimeMillis())
        val baseYield = when (crop.lowercase()) {
            "wheat" -> 45.0 // quintals/ha
            "rice", "paddy" -> 60.0
            "tomato" -> 300.0
            "maize", "corn" -> 50.0
            else -> 40.0
        }

        // 1. Nutrient Impact (Optimized for NPK ranges)
        val nFactor = 1.0 - (n - 80).coerceIn(-40.0, 40.0) / 400.0
        val pFactor = 1.0 - (p - 30).coerceIn(-15.0, 15.0) / 150.0
        val kFactor = 1.0 - (k - 180).coerceIn(-90.0, 90.0) / 900.0

        // 2. Climate Impact (Temp optimal around 25C, precip optimal around 150mm)
        val tempDeviation = (temp - 25.0).coerceIn(-10.0, 10.0)
        val precipDeviation = (precip - 150.0).coerceIn(-100.0, 100.0)
        val tempFactor = 1.0 - (tempDeviation * tempDeviation) / 200.0
        val precipFactor = 1.0 - (precipDeviation * precipDeviation) / 40000.0

        // 3. Final Calculation (including random deviation and risk)
        var expectedYield = baseYield * nFactor * pFactor * kFactor * tempFactor * precipFactor
        expectedYield = expectedYield * (1.0 + random.nextDouble(-0.05, 0.05)) // 5% random noise
        expectedYield = expectedYield * (1.0 - risk) // Apply disease/stress risk

        // 4. Determine Growth Stage (Simplified, based on time/season, mocked here)
        val stages = listOf("Seedling", "Vegetative Growth", "Flowering/Fruiting", "Maturity", "Harvest Ready")
        val stageIndex = random.nextInt(0, stages.size)
        val growthStage = stages[stageIndex]

        // 5. Schedule Optimization and Alerts
        var optimalSchedule = "Next Irrigation: Tomorrow. Next Fertilization: In 5 days."
        var reductionAlert = "None"
        var reductionPercentage = (risk * 100).toInt()

        if (risk > 0.1) {
            reductionAlert = "Potential **${reductionPercentage}% yield reduction** due to high humidity/soil deficiency. Focus on pest control."
        }

        if (growthStage == "Maturity" || growthStage == "Harvest Ready") {
            optimalSchedule = "Harvest Window: **Starting Next Week**. Monitor moisture."
        }

        // Clean markdown for simple notification message
        val cleanReductionAlert = reductionAlert.replace("**", "")
        val cleanOptimalSchedule = optimalSchedule.replace("**", "")

        return YieldForecast(
            crop = crop,
            expectedYield = expectedYield.coerceAtLeast(baseYield * 0.5), // Min yield cap
            growthStage = growthStage,
            reductionAlert = cleanReductionAlert,
            optimalSchedule = cleanOptimalSchedule
        )
    }

    /**
     * Creates and displays a notification based on the yield prediction results, including Plot ID.
     * 🔥 UPDATED: Includes a PendingIntent to redirect to the YieldPredictionFragment in HomeActivity.
     */
    private fun showNotification(forecast: YieldForecast, farmId: String) {
        // --- START FIX: Runtime Permission Check ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(WORK_TAG, "Notification permission denied. Cannot show yield update.")
            return
        }
        // --- END FIX ---

        val formattedYield = String.format("%.2f", forecast.expectedYield)
        val plotName = farmId.replace("_", " ") // Convert ID back to readable name

        val title = "🌾 Yield Forecast (${plotName}): ${formattedYield} Qt/Ha"
        var message = "${forecast.growthStage}. Schedule: ${forecast.optimalSchedule}"

        // Check for severe alerts
        val priority = if (forecast.reductionAlert != "None") {
            message = "⚠️ ALERT: ${forecast.reductionAlert}"
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }

        createNotificationChannel()

        // Use a unique notification ID based on the plot ID hash code
        val notificationId = NOTIFICATION_ID + farmId.hashCode()

        // --- START REDIRECTION LOGIC ---
        // 1. Create the Intent for HomeActivity, targeting the Yield Prediction Fragment
        val intent = Intent(applicationContext, HomeActivity::class.java).apply {
            putExtra(HomeActivity.NOTIFICATION_TARGET_FRAGMENT_KEY, HomeActivity.FRAGMENT_YIELD)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // 2. Create the PendingIntent
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId, // Use the specific notification ID
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // --- END REDIRECTION LOGIC ---


        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setContentIntent(pendingIntent) // 🔥 Attach the PendingIntent
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            notify(notificationId, builder.build())
        }
    }

    /**
     * Creates the Notification Channel, required for Android 8.0 (Oreo) and above.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Yield Prediction Alerts"
            val descriptionText = "Alerts regarding expected yield, growth stages, and schedule optimization."
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