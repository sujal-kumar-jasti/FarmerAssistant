package com.farmerassistant.app.ui.home.fragments

import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import android.location.Geocoder
import androidx.preference.PreferenceManager
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.farmerassistant.app.R
import com.google.gson.Gson
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.farmerassistant.app.workers.SoilAnalysisWorker
import android.widget.Toast
import kotlinx.coroutines.tasks.await
import com.google.android.material.textfield.TextInputLayout
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.setPadding
import com.farmerassistant.app.workers.ClimateWorker

// Data class to hold simple forecast data
data class DailyForecast(
    val day: String,
    val maxTemp: Int,
    val minTemp: Int,
    val condition: String,
    val iconId: String
)

// Data class to hold soil health data (Phase 16)
data class SoilData(
    val nitrogen: Int,
    val phosphorus: Int,
    val potassium: Int,
    val status: String,
    val recommendation: String,
    val irrigationAdvice: String = "No water advice." // Phase 16 field
)

// Data class for caching
data class CachedWeather(val current: String, val forecast: String)

class ClimateFragment : Fragment() {

    // --- MEMBER VARIABLE DECLARATIONS (FIXED) ---
    private lateinit var tvLocationName: TextView
    private lateinit var tvCondition: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvFeelsLike: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvWindSpeed: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvSunTimes: TextView
    private lateinit var ivWeatherIcon: ImageView
    private lateinit var ivWindDirection: ImageView
    private lateinit var tvFarmingStatus: TextView
    private lateinit var cardFarmingStatus: MaterialCardView
    private lateinit var forecastContainer: LinearLayout

    private lateinit var tilFarmSelector: TextInputLayout
    private lateinit var actFarmSelector: AutoCompleteTextView

    // Soil Health UI elements
    private lateinit var tvSoilNPK: TextView
    private lateinit var tvSoilRecommendation: TextView
    private lateinit var cardSoilHealth: MaterialCardView
    private lateinit var btnRunSoilAnalysis: MaterialButton

    // Phase 16: NEW UI element for Irrigation Advice
    private lateinit var tvIrrigationAdvice: TextView
    // --- END DECLARATIONS ---


    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient()
    private val gson = Gson()

    private var farmList: List<FarmField> = emptyList()
    private var selectedFarm: FarmField? = null

    private val apiKey = "c4fcb8a7c355e31838a382beae58bde6"

    private val colorSun = R.color.md_theme_errorContainer
    private val colorRain = R.color.md_theme_tertiary
    private val colorCloudBlue = R.color.md_theme_secondaryContainer
    private val colorDefault = R.color.md_theme_onPrimaryContainer
    private val FIXED_CLOUD_BLUE = android.R.color.holo_blue_light
    private val colorOutlineVariant = android.R.color.darker_gray

    private fun Int.dpToPx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    private fun getMaterialStyle(styleName: String): Int {
        return resources.getIdentifier(styleName, "style", requireContext().packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(
                "TextAppearance_MaterialComponents_TitleSmall",
                "style",
                "com.google.android.material"
            )
            ?: android.R.style.TextAppearance_Material_Title
    }

    private fun getMaterialBodyStyle(styleName: String): Int {
        return resources.getIdentifier(styleName, "style", requireContext().packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(
                "TextAppearance_MaterialComponents_BodyMedium",
                "style",
                "com.google.android.material"
            )
            ?: android.R.style.TextAppearance_Medium
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_climate, container, false)

        // --- BINDINGS ---
        tvLocationName = v.findViewById(R.id.tvLocationName)
        tvCondition = v.findViewById(R.id.tvCondition)
        tvTemperature = v.findViewById(R.id.tvTemperature)
        tvFeelsLike = v.findViewById(R.id.tvFeelsLike)
        tvHumidity = v.findViewById(R.id.tvHumidity)
        tvWindSpeed = v.findViewById(R.id.tvWindSpeed)
        tvPressure = v.findViewById(R.id.tvPressure)
        tvSunTimes = v.findViewById(R.id.tvSunTimes)
        ivWeatherIcon = v.findViewById(R.id.ivWeatherIcon)
        ivWindDirection = v.findViewById(R.id.ivWindDirection)
        tvFarmingStatus = v.findViewById(R.id.tvFarmingStatus)
        cardFarmingStatus = v.findViewById(R.id.cardFarmingStatus)
        forecastContainer = v.findViewById(R.id.forecastContainer)
        btnRunSoilAnalysis = v.findViewById(R.id.btnRunSoilAnalysis)
        tilFarmSelector = v.findViewById(R.id.tilFarmSelector)
        actFarmSelector = v.findViewById(R.id.actFarmSelector)

        // Bind Soil Health UI elements
        tvSoilNPK = v.findViewById(R.id.tvSoilNPK)
        tvSoilRecommendation = v.findViewById(R.id.tvSoilRecommendation)
        cardSoilHealth = v.findViewById(R.id.cardSoilHealth)
        tvIrrigationAdvice = v.findViewById(R.id.tvIrrigationAdvice) // Phase 16 Binding
        // --- END BINDINGS ---


        // Set initial state
        tvLocationName.text = "Loading..."
        tvCondition.text = "Fetching weather data..."
        tvTemperature.text = "--°C"
        tvFeelsLike.text = "--°C"
        tvHumidity.text = "--%"
        tvWindSpeed.text = "-- m/s"
        tvPressure.text = "-- hPa"
        tvSunTimes.text = "-- / --"
        tvFarmingStatus.text = "Checking farming conditions..."
        tvSoilNPK.text = "N/P/K: -- / -- / --"
        tvSoilRecommendation.text = "Loading soil health data..."
        tvIrrigationAdvice.text = "Water management advice loading..." // Phase 16 Initial Text

        // --- PLOT SELECTOR LISTENER (Dialog) ---
        actFarmSelector.setOnClickListener {
            showPlotSelectionDialog()
        }
        actFarmSelector.setText(selectedFarm?.name ?: "  Select Plot...", false)
        // --- END PLOT SELECTOR LISTENER ---


        if (apiKey == "YOUR_OPENWEATHER_API_KEY_HERE" || apiKey.isEmpty()) {
            tvCondition.text = "⚠️ API Key Missing: Please insert OpenWeather API Key."
        }
        btnRunSoilAnalysis.setOnClickListener {
            runOneTimeSoilAnalysis()
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val shouldRefresh = prefs.getBoolean("should_refresh_climate", false)

        if (apiKey.isNotEmpty() && (shouldRefresh || isFirstLoad())) {
            loadFarmLocationFromFirestore()

            if (shouldRefresh) {
                prefs.edit().putBoolean("should_refresh_climate", false).apply()
            }
        }
    }

    private fun loadFarmListAndInitialData() {
        val uid = auth.currentUser?.uid ?: return
        setLoadingState()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userDoc = db.collection("users").document(uid).get().await()

                @Suppress("UNCHECKED_CAST")
                val farmData = userDoc.get("farm_data") as? Map<String, Any>

                if (farmData == null) {
                    Log.e("ClimateFragment", "Error: 'farm_data' map is missing or null.")
                    throw IllegalStateException("Farm data not found.")
                }

                // --- Type Safe Extraction ---
                val plotNames = farmData["plots_names"] as? List<String> ?: emptyList()
                val plotsCrops = farmData["plots_crops"] as? List<String> ?: emptyList()
                val coordsMapList = farmData["plots_coordinates_flat"] as? List<Map<String, Any>> ?: emptyList()
                // --- End Type Safe Extraction ---

                val primaryLat = userDoc.getDouble("lat") ?: 0.0
                val primaryLng = userDoc.getDouble("lng") ?: 0.0
                val primaryCrop = userDoc.getString("crops_grown")?.split(",")?.firstOrNull()?.trim() ?: "Paddy"

                val currentFields = mutableListOf<FarmField>()

                // --- RECONSTRUCTION LOGIC ---
                var currentPlotCoords = mutableListOf<Map<String, Any>>()
                var attributeIndex = 0

                if (plotNames.isEmpty() || coordsMapList.isEmpty()) {
                    Log.w("ClimateFragment", "Skipping plot reconstruction due to missing list data.")
                } else {

                    for (coordMap in coordsMapList) {
                        val isSeparator = coordMap["separator"] == true

                        if (isSeparator) {
                            if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                                val anchorPoint = currentPlotCoords.first()
                                val name = plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                                val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                                val anchorLat = anchorPoint["latitude"] as? Double ?: 0.0
                                val anchorLng = anchorPoint["longitude"] as? Double ?: 0.0

                                if (anchorLat != 0.0 || anchorLng != 0.0) {
                                    currentFields.add(
                                        FarmField(
                                            id = name.replace(" ", "_"),
                                            name = name,
                                            crop = crop,
                                            lat = anchorLat,
                                            lng = anchorLng
                                        )
                                    )
                                }
                                attributeIndex++
                            }
                            currentPlotCoords = mutableListOf()
                        } else {
                            currentPlotCoords.add(coordMap)
                        }
                    }

                    // Handle the last plot
                    if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                        val anchorPoint = currentPlotCoords.first()
                        val name = plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                        val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                        val anchorLat = anchorPoint["latitude"] as? Double ?: 0.0
                        val anchorLng = anchorPoint["longitude"] as? Double ?: 0.0

                        if (anchorLat != 0.0 || anchorLng != 0.0) {
                            currentFields.add(
                                FarmField(
                                    id = name.replace(" ", "_"),
                                    name = name,
                                    crop = crop,
                                    lat = anchorLat,
                                    lng = anchorLng
                                )
                            )
                        }
                    }
                }

                // FALLBACK: If no fields were reconstructed, use the single primary anchor
                if (currentFields.isEmpty() && primaryLat != 0.0) {
                    val fallbackName = "Primary Farm Location"
                    currentFields.add(
                        FarmField(
                            "primary",
                            fallbackName,
                            primaryCrop,
                            primaryLat,
                            primaryLng
                        )
                    )
                }

                if (currentFields.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvLocationName.text = "No farm location set."
                        setReadyState()
                    }
                    return@launch
                }

                farmList = currentFields.toList()
                selectedFarm = farmList.firstOrNull()


                withContext(Dispatchers.Main) {
                    setReadyState()

                    actFarmSelector.setText(selectedFarm!!.name, false)
                    tvLocationName.text = selectedFarm!!.name

                    updateFarmData(selectedFarm!!.lat, selectedFarm!!.lng)

                    loadSoilHealthFromFirestore()
                }

            } catch (e: Exception) {
                Log.e("ClimateFragment", "FATAL DATA ERROR: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    tvLocationName.text = "Error: Data structure is incorrect."
                    setReadyState()
                }
            }
        }
    }

    // === Reliable Dialog Selection ===
    private fun showPlotSelectionDialog() {
        if (farmList.isEmpty()) {
            Toast.makeText(requireContext(), "No farm plots found.", Toast.LENGTH_SHORT).show()
            return
        }

        val farmNames = farmList.map { it.name }.toTypedArray()

        val checkedItemIndex = farmList.indexOfFirst { it.id == selectedFarm?.id }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Farm Plot")
            .setSingleChoiceItems(farmNames, checkedItemIndex) { dialog, which ->
                val selectedFarmName = farmNames[which]
                val newFarm = farmList.find { it.name == selectedFarmName }

                if (newFarm != null) {
                    if (selectedFarm?.id != newFarm.id) {
                        selectedFarm = newFarm
                        actFarmSelector.setText(newFarm.name, false)
                        updateFarmData(newFarm.lat, newFarm.lng)
                        loadSoilHealthFromFirestore()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    // ===============================================

    private fun isFirstLoad(): Boolean {
        return tvTemperature.text.toString() == "--°C"
    }

    private fun setLoadingState() {
        btnRunSoilAnalysis.isEnabled = false
        tvLocationName.text = "Loading farm list..."
        tvCondition.text = "Fetching weather data..."
        tvTemperature.text = "--°C"
        tvFeelsLike.text = "--°C"
        tvHumidity.text = "--%"
        tvWindSpeed.text = "-- m/s"
        tvPressure.text = "-- hPa"
        tvIrrigationAdvice.text = "Water management advice loading..." // Phase 16 Reset
    }

    private fun setReadyState() {
        btnRunSoilAnalysis.isEnabled = true
    }

    private fun updateFarmData(lat: Double, lng: Double) {
        tvCondition.text = "Fetching weather for selected farm..."
        geocodeAndSaveLocation(lat, lng)
        fetchCurrentAndForecastWeather(lat, lng)
    }

    private fun runOneTimeSoilAnalysis() {
        val farm = selectedFarm
        if (farm == null) {
            Toast.makeText(context, "Please select a plot first.", Toast.LENGTH_SHORT).show()
            return
        }

        val data = androidx.work.Data.Builder()
            .putDouble("input_lat", farm.lat)
            .putDouble("input_lng", farm.lng)
            .putString("input_crop", farm.crop)
            .putString("input_farm_id", farm.id)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequest.Builder(SoilAnalysisWorker::class.java)
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        WorkManager.getInstance(requireContext()).enqueue(workRequest)

        btnRunSoilAnalysis.isEnabled = false
        tvSoilRecommendation.text = "Analysis requested. Waiting for result..."
        tvIrrigationAdvice.text = "Irrigation prediction requested..."

        WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(workRequest.id)
            .observe(viewLifecycleOwner) { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    btnRunSoilAnalysis.isEnabled = true

                    if (isAdded) {
                        loadSoilHealthFromFirestore()
                        Toast.makeText(requireContext(), "Soil analysis complete!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun loadFarmLocationFromFirestore() {
        val uid = auth.currentUser?.uid ?: return
        tvCondition.text = "Loading profile data from Firestore..."
        loadFarmListAndInitialData()
    }

    // 2. Load Soil Health (Phase 16 updated)
    private fun loadSoilHealthFromFirestore() {
        val uid = auth.currentUser?.uid ?: return
        val plotId = selectedFarm?.id ?: return

        val soilCacheKey = "cached_soil_data_${plotId}"
        tvSoilRecommendation.text = "Fetching daily soil analysis..."
        tvIrrigationAdvice.text = "Fetching irrigation advice..."

        // --- ASYNCHRONOUS FIRESTORE FETCH ---
        db.collection(SoilAnalysisWorker.SOIL_COLLECTION).document("${uid}_${plotId}").get().addOnSuccessListener { doc ->
            if (!isAdded) return@addOnSuccessListener

            if (doc.exists()) {
                val n = doc.getDouble("nitrogen")?.toInt() ?: 0
                val p = doc.getDouble("phosphorus")?.toInt() ?: 0
                val k = doc.getDouble("potassium")?.toInt() ?: 0
                val status = doc.getString("status") ?: "N/A"
                val recommendation = doc.getString("recommendation") ?: "No recommendation available."
                val irrigationAdvice = doc.getString("irrigationAdvice") ?: "No water advice."
                val farmName = doc.getString("farm_name") ?: "N/A"

                val soilData = SoilData(n, p, k, status, recommendation, irrigationAdvice)

                displaySoilHealth(soilData)
                Log.d("ClimateFragment", "Soil health data loaded from Firestore for plot: $farmName.")

                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                prefs.edit().putString(soilCacheKey, gson.toJson(soilData)).apply()

            } else {
                Log.w("ClimateFragment", "No recent soil analysis found in Firestore. Attempting local cache.")
                loadSoilHealthFromCache(soilCacheKey)
            }
        }.addOnFailureListener { e ->
            Log.e("ClimateFragment", "Soil fetch failed: ${e.message}. Falling back to cache.", e)
            if (isAdded) {
                loadSoilHealthFromCache(soilCacheKey, isNetworkError = true)
            }
        }
    }

    // Load Soil Health from Cache (Phase 16 updated)
    private fun loadSoilHealthFromCache(cacheKey: String, isNetworkError: Boolean = false) {
        if (!isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val cachedJson = prefs.getString(cacheKey, null)

        if (cachedJson != null) {
            try {
                val cachedData = gson.fromJson(cachedJson, SoilData::class.java)
                displaySoilHealth(cachedData)

                val status = if (isNetworkError) " (OFFLINE CACHE)" else " (CACHED DATA)"
                tvSoilRecommendation.append(status)

            } catch (e: Exception) {
                Log.e("ClimateFragment", "Corrupt soil data found in cache.")
                showNoSoilDataMessage()
            }
        } else {
            showNoSoilDataMessage(isNetworkError)
        }
    }

    private fun showNoSoilDataMessage(isNetworkError: Boolean = false) {
        tvSoilNPK.text = "N/P/K: -- / -- / --"
        tvSoilRecommendation.text = if (isNetworkError) {
            "Network connection failed. No cached soil data available for ${selectedFarm?.name}."
        } else {
            "No recent soil analysis found for ${selectedFarm?.name}. Run analysis now."
        }
        tvIrrigationAdvice.text = "No recent irrigation advice available." // Phase 16 No Advice
        cardSoilHealth.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_theme_secondaryContainer))
    }

    // 3. Geocode and Save Location (UNCHANGED)
    private fun geocodeAndSaveLocation(lat: Double, lng: Double) {
        if (!isAdded) return

        val geocoder = Geocoder(requireContext(), Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)

                if (isAdded) {
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

                        val state = address.adminArea ?: "India"
                        val district = address.locality ?: address.subAdminArea ?: "a general region"

                        prefs.edit()
                            .putString("user_state", state)
                            .putString("user_district", district)
                            .apply()

                        withContext(Dispatchers.Main) {
                            tvLocationName.text = "${address.featureName}, ${district}"
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("ClimateFragment", "Geocoding failed: ${e.message}. Using cached location.", e)
            } catch (e: Exception) {
                Log.e("ClimateFragment", "Geocoding failed: ${e.message}")
            }
        }
    }

    /**
     * Phase 16: Display function now handles irrigation advice and coloring.
     */
    private fun displaySoilHealth(data: SoilData) {
        tvSoilNPK.text = "N/P/K: ${data.nitrogen} / ${data.phosphorus} / ${data.potassium} kg/ha"
        tvSoilRecommendation.text = "${data.status}: ${data.recommendation}"

        // Phase 16: Display the Irrigation Advice
        val adviceText = data.irrigationAdvice
        tvIrrigationAdvice.text = adviceText

        // Conditional coloring for high-risk irrigation alerts
        val adviceColor = when {
            adviceText.contains("IMMEDIATE", ignoreCase = true) -> R.color.md_theme_error
            adviceText.contains("STOP IRRIGATION", ignoreCase = true) -> R.color.md_theme_error
            adviceText.contains("SCHEDULE", ignoreCase = true) -> R.color.md_theme_primary
            else -> R.color.md_theme_onSurfaceVariant // Default/Optimal color
        }
        tvIrrigationAdvice.setTextColor(ContextCompat.getColor(requireContext(), adviceColor))

        // Set card color based on status
        val color = when (data.status) {
            "Optimal" -> R.color.whatsapp_green
            "Nutrient Excess" -> R.color.whatsapp_light_green
            else -> R.color.md_theme_errorContainer
        }
        cardSoilHealth.setCardBackgroundColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun fetchCurrentAndForecastWeather(lat: Double, lng: Double) {
        tvCondition.text = "Fetching weather for your farm..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentUrl =
                    "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lng&appid=$apiKey&units=metric"
                val forecastUrl =
                    "https://api.openweathermap.org/data/2.5/forecast?lat=$lat&lon=$lng&appid=$apiKey&units=metric"

                // Fetch Current Weather
                val currentWeather = client.newCall(Request.Builder().url(currentUrl).build()).execute().use { response ->
                    val responseBody = response.body?.string() ?: throw IOException("Empty current weather response.")
                    if (!response.isSuccessful || JSONObject(responseBody).optString("cod") != "200") {
                        throw IOException("Current Weather API failed. Code: ${response.code}. Body: $responseBody")
                    }
                    responseBody
                }
                // Fetch 5-Day Forecast
                val forecastResponse = client.newCall(Request.Builder().url(forecastUrl).build()).execute().use { response ->
                    val responseBody = response.body?.string() ?: throw IOException("Empty forecast response.")
                    if (!response.isSuccessful || JSONObject(responseBody).optString("cod") != "200") {
                        throw IOException("Forecast Weather API failed. Code: ${response.code}. Body: $responseBody")
                    }
                    responseBody
                }

                saveCachedWeather(currentWeather, forecastResponse)
                saveClimateDataToFirestore(currentWeather)

                val currentData = parseCurrentWeatherJson(currentWeather)
                val dailyForecasts = parseForecastJson(forecastResponse)

                withContext(Dispatchers.Main) {
                    updateUI(currentData)
                    analyzeFarmingConditions(currentData)
                    displayForecast(dailyForecasts)
                }
            } catch (e: Exception) {
                if (view != null) {
                    withContext(Dispatchers.Main) {
                        tvCondition.text = "Network Error. Loading cached weather data..."
                        loadCachedWeather()
                    }
                }
            }
        }
    }

    private suspend fun saveClimateDataToFirestore(currentWeatherJson: String) {
        val uid = auth.currentUser?.uid ?: return
        val plotId = selectedFarm?.id ?: return
        val json = JSONObject(currentWeatherJson)

        val main = json.optJSONObject("main")
        val wind = json.optJSONObject("wind")
        val clouds = json.optJSONObject("clouds")
        val rain = json.optJSONObject("rain")

        val dataToSave = hashMapOf<String, Any>(
            "temp_avg" to (main?.optDouble("temp") ?: 0.0),
            "humidity" to (main?.optInt("humidity") ?: 0),
            "pressure" to (main?.optInt("pressure") ?: 0),
            "wind_speed" to (wind?.optDouble("speed") ?: 0.0),
            "cloudiness" to (clouds?.optInt("all") ?: 0),
            "precip_24h" to (rain?.optDouble("1h") ?: 0.0) + (rain?.optDouble("3h") ?: 0.0),
            "timestamp" to System.currentTimeMillis()
        )

        try {
            db.collection(ClimateWorker.CLIMATE_COLLECTION).document("${uid}_${plotId}").set(dataToSave).await()
        } catch (e: Exception) {
            Log.e("ClimateFragment", "Failed to save climate data: ${e.message}")
        }
    }


    private fun saveCachedWeather(currentJson: String, forecastJson: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val cached = CachedWeather(currentJson, forecastJson)
        prefs.edit().putString("cached_weather", gson.toJson(cached)).apply()
    }

    private fun loadCachedWeather() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val cachedJson = prefs.getString("cached_weather", null)

        if (cachedJson != null) {
            try {
                val cached = gson.fromJson(cachedJson, CachedWeather::class.java)

                val currentData = parseCurrentWeatherJson(cached.current)
                val dailyForecasts = parseForecastJson(cached.forecast)

                updateUI(currentData)
                analyzeFarmingConditions(currentData)
                displayForecast(dailyForecasts)

                tvCondition.append(" (OFFLINE)")
                tvFarmingStatus.text = HtmlCompat.fromHtml("<b>OFFLINE MODE:</b> Data may be outdated. Last successful fetch displayed.", HtmlCompat.FROM_HTML_MODE_LEGACY)
                cardFarmingStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.md_theme_surfaceVariant))

            } catch (e: Exception) {
                tvCondition.text = "No network. Cached data is corrupt or unavailable."
            }
        } else {
            tvCondition.text = "No network connection and no cached data available."
        }
    }

    private fun parseCurrentWeatherJson(jsonString: String): Map<String, String> {
        val json = JSONObject(jsonString)

        if (json.has("cod") && json.getString("cod") != "200") {
            throw IOException("Weather API Error: ${json.optString("message", "Unknown API error")}")
        }

        val main = json.optJSONObject("main") ?: throw IOException("Weather data not found in response.")
        val weatherArray = json.getJSONArray("weather").getJSONObject(0)
        val wind = json.getJSONObject("wind")
        val sys = json.getJSONObject("sys")
        val timezoneOffset = json.getInt("timezone")

        fun formatTime(unixTime: Long, offset: Int): String {
            val date = Date(unixTime * 1000L)
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone(TimeZone.getAvailableIDs(offset * 1000).firstOrNull() ?: "UTC")
            return sdf.format(date)
        }

        val temp = "%.1f".format(main.getDouble("temp"))
        val feelsLike = "%.1f".format(main.getDouble("feels_like"))
        val description = weatherArray.getString("description").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        return mapOf(
            "locationName" to json.getString("name"),
            "temp" to temp,
            "feelsLike" to feelsLike,
            "humidity" to main.getInt("humidity").toString(),
            "pressure" to main.optString("pressure", "--"),
            "windSpeed" to "%.1f".format(wind.getDouble("speed")),
            "description" to description,
            "sunrise" to formatTime(sys.getLong("sunrise"), timezoneOffset),
            "sunset" to formatTime(sys.getLong("sunset"), timezoneOffset),
            "windDeg" to wind.optString("deg", "0"),
            "iconId" to weatherArray.getString("icon")
        )
    }

    private fun parseForecastJson(jsonString: String): List<DailyForecast> {
        val json = JSONObject(jsonString)
        if (json.optString("cod") != "200") return emptyList()

        val forecastList = json.getJSONArray("list")
        val dailyMap = mutableMapOf<String, MutableList<JSONObject>>()
        val sdf = SimpleDateFormat("EEE", Locale.US)

        for (i in 0 until forecastList.length()) {
            val item = forecastList.getJSONObject(i)
            val timestamp = item.getLong("dt") * 1000L
            val day = sdf.format(Date(timestamp))

            if (day != sdf.format(Date())) {
                dailyMap.getOrPut(day) { mutableListOf() }.add(item)
            }
        }

        val resultList = mutableListOf<DailyForecast>()

        dailyMap.forEach { (day, forecasts) ->
            if (forecasts.isNotEmpty()) {
                val minTemp = forecasts.minOf { it.getJSONObject("main").getDouble("temp_min") }.toInt()
                val maxTemp = forecasts.maxOf { it.getJSONObject("main").getDouble("temp_max") }.toInt()

                val representativeForecast = forecasts.getOrNull(3) ?: forecasts.first()
                val conditionObject = representativeForecast.getJSONArray("weather").getJSONObject(0)

                val iconId = conditionObject.getString("icon")
                val description = conditionObject.getString("description").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

                resultList.add(DailyForecast(day, maxTemp, minTemp, description, iconId))
            }
        }

        return resultList.take(5)
    }


    private fun displayForecast(forecasts: List<DailyForecast>) {
        val container = view?.findViewById<LinearLayout>(R.id.forecastContainer) ?: return
        container.removeAllViews()

        if (forecasts.isEmpty()) {
            val tvNoData = TextView(requireContext()).apply {
                text = "5-day Forecast data unavailable for this period."
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
                setPadding(0, 10, 0, 10)
            }
            container.addView(tvNoData)
            return
        }

        val iconSizePx = 32.dpToPx()
        val marginPx = 8.dpToPx()
        val separatorThicknessPx = 1.dpToPx()

        val titleSmallStyle = getMaterialStyle("TextAppearance_Material3_TitleSmall")
        val bodyMediumStyle = getMaterialBodyStyle("TextAppearance_Material3_BodyMedium")


        forecasts.forEach { forecast ->
            val (iconResId, _) = getWeatherIconDetails(forecast.iconId)

            val itemLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
            }

            val tvDay = TextView(requireContext()).apply {
                text = forecast.day
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                @Suppress("DEPRECATION")
                setTextAppearance(context, titleSmallStyle)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            }

            val ivIcon = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                    marginStart = marginPx / 2
                    marginEnd = marginPx / 2
                }
                setImageResource(iconResId)

                if (iconResId == R.drawable.ic_cloud) {
                    setColorFilter(ContextCompat.getColor(requireContext(), FIXED_CLOUD_BLUE))
                } else {
                    clearColorFilter()
                }
            }

            val tvDesc = TextView(requireContext()).apply {
                text = forecast.condition
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
                @Suppress("DEPRECATION")
                setTextAppearance(context, bodyMediumStyle)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f).apply {
                    marginStart = marginPx / 2
                    marginEnd = marginPx
                }
            }

            val tvTemp = TextView(requireContext()).apply {
                text = "${forecast.maxTemp}° / ${forecast.minTemp}°"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface))
                @Suppress("DEPRECATION")
                setTextAppearance(context, titleSmallStyle)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = marginPx
                }
            }

            itemLayout.addView(tvDay)
            itemLayout.addView(ivIcon)
            itemLayout.addView(tvDesc)
            itemLayout.addView(tvTemp)

            container.addView(itemLayout)

            if (forecast != forecasts.last()) {
                val separator = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, separatorThicknessPx)
                    setBackgroundColor(ContextCompat.getColor(requireContext(), colorOutlineVariant))
                }
                container.addView(separator)
            }
        }
    }


    private fun updateUI(data: Map<String, String>) {
        tvLocationName.text = data["locationName"] ?: "Unknown Location"
        tvCondition.text = data["description"] ?: "N/A"
        tvTemperature.text = "${data["temp"] ?: "--"}°C"
        tvFeelsLike.text = "Feels like: ${data["feelsLike"] ?: "--"}°C"
        tvHumidity.text = "${data["humidity"] ?: "--"}%"
        tvWindSpeed.text = "${data["windSpeed"] ?: "--"} m/s"
        tvPressure.text = "${data["pressure"] ?: "--"} hPa"
        tvSunTimes.text = "${data["sunrise"] ?: "--"} / ${data["sunset"] ?: "--"}"

        val iconId = data["iconId"] ?: "01d"
        val (iconResId, _) = getWeatherIconDetails(iconId)

        ivWeatherIcon.setImageResource(iconResId)

        if (iconResId == R.drawable.ic_cloud) {
            ivWeatherIcon.setColorFilter(ContextCompat.getColor(requireContext(), FIXED_CLOUD_BLUE))
        } else {
            ivWeatherIcon.clearColorFilter()
        }

        val windDeg = data["windDeg"]?.toFloatOrNull() ?: 0f
        ivWindDirection.rotation = windDeg
    }

    private fun getWeatherIconDetails(iconId: String): Pair<Int, Int> {
        val defaultIcon = R.drawable.ic_cloud
        val defaultColor = R.color.md_theme_onPrimaryContainer

        return when (iconId.substring(0, 2)) {
            "01" -> Pair(R.drawable.ic_sun, R.color.md_theme_errorContainer)
            "02" -> Pair(R.drawable.ic_partly_cloudy, R.color.md_theme_errorContainer)
            "03" -> Pair(R.drawable.ic_cloud, R.color.md_theme_secondaryContainer)
            "04" -> Pair(R.drawable.ic_cloud, R.color.md_theme_secondaryContainer)
            "09", "10" -> Pair(R.drawable.ic_rain, R.color.md_theme_tertiary)
            "11" -> Pair(R.drawable.ic_thunderstorm, R.color.md_theme_tertiary)
            "13" -> Pair(R.drawable.ic_snow, R.color.md_theme_tertiary)
            "50" -> Pair(R.drawable.ic_mist, defaultColor)
            else -> Pair(defaultIcon, R.color.md_theme_secondaryContainer)
        }
    }

    private fun analyzeFarmingConditions(data: Map<String, String>) {
        val temp = data["temp"]?.toFloatOrNull() ?: 25f
        val humidity = data["humidity"]?.toIntOrNull() ?: 50
        val windSpeed = data["windSpeed"]?.toFloatOrNull() ?: 0f

        var statusText = "Conditions are <b>Normal</b> for farming. Continue routine activities."
        var cardBackground = R.color.whatsapp_green
        var textColor = R.color.white

        if (temp > 35) {
            statusText = "🚨 <b>ABNORMAL: HEAT STRESS!</b> Temperature is high (${temp}°C). Increase irrigation and avoid spraying."
            cardBackground = R.color.error_red
            textColor = R.color.white
        } else if (humidity > 85) {
            statusText = "⚠️ <b>HIGH: DISEASE RISK!</b> Humidity is very high (${humidity}%). Monitor plants for fungal diseases."
            cardBackground = R.color.md_theme_primaryContainer
            textColor = R.color.black
        } else if (windSpeed > 10) {
            statusText = "💨 <b>ABNORMAL: WIND!</b> Wind speed is high (${windSpeed} m/s). Secure young plants and avoid insecticide application."
            cardBackground = R.color.md_theme_primaryContainer
            textColor = R.color.black
        } else {
            cardBackground = R.color.whatsapp_green
            textColor = R.color.white
        }

        requireActivity().runOnUiThread {
            tvFarmingStatus.text = HtmlCompat.fromHtml(statusText, HtmlCompat.FROM_HTML_MODE_LEGACY)

            cardFarmingStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), cardBackground))
            tvFarmingStatus.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        }
    }
}