package com.farmerassistant.app.ui.home.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.farmerassistant.app.R
import com.farmerassistant.app.workers.YieldPredictionWorker
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.farmerassistant.app.workers.SoilAnalysisWorker
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.farmerassistant.app.workers.YieldForecast

// Assuming these data classes are accessible or defined above the fragment
data class FarmField(
    val id: String,
    val name: String,
    val crop: String,
    val lat: Double,
    val lng: Double,
    val district: String? = null
)

class YieldPredictionFragment : Fragment() {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val gson = Gson()
    private var farmList: List<FarmField> = emptyList()
    private var selectedFarm: FarmField? = null

    // UI Bindings
    private lateinit var tvCropYield: TextView
    private lateinit var tvGrowthStage: TextView
    private lateinit var tvSchedule: TextView
    private lateinit var tvAlert: TextView
    private lateinit var btnRunAnalysis: MaterialButton
    private lateinit var btnGoToMarket: MaterialButton
    private lateinit var tilFarmSelector: TextInputLayout
    private lateinit var actFarmSelector: AutoCompleteTextView
    private lateinit var tvYieldLocation: TextView

    private val YIELD_CACHE_KEY_BASE = "cached_yield_forecast_"


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_yield_prediction, container, false)

        // Bindings
        tvCropYield = v.findViewById(R.id.tvCropYield)
        tvGrowthStage = v.findViewById(R.id.tvGrowthStage)
        tvSchedule = v.findViewById(R.id.tvSchedule)
        tvAlert = v.findViewById(R.id.tvAlert)
        btnRunAnalysis = v.findViewById(R.id.btnRunYieldAnalysis)
        btnGoToMarket = v.findViewById(R.id.btnGoToMarket)
        tvYieldLocation = v.findViewById(R.id.tvYieldLocation)
        tilFarmSelector = v.findViewById(R.id.tilFarmSelector)
        actFarmSelector = v.findViewById(R.id.actFarmSelector)

        // Set initial text for the selector field
        actFarmSelector.setText(selectedFarm?.name ?: getString(R.string.hint_select_farm), false)

        loadFarmListAndInitialData()
        setupListeners()

        return v
    }

    private fun setupListeners() {
        btnRunAnalysis.setOnClickListener {
            runYieldAnalysis()
        }

        // --- CRITICAL FIX: Replace the faulty dropdown listener with dialog trigger ---
        actFarmSelector.setOnClickListener {
            showPlotSelectionDialog()
        }
        // --- END FIX ---

        // NEW: Listener to navigate to MarketFragment, passing selected farm details
        btnGoToMarket.setOnClickListener {
            val farm = selectedFarm
            if (isAdded && parentFragmentManager != null && farm != null) {
                val marketFragment = MarketFragment().apply {
                    arguments = Bundle().apply {
                        // CRITICAL: Pass the selected farm's coordinates
                        putDouble("market_lat", farm.lat)
                        putDouble("market_lng", farm.lng)
                        putString("target_crop", farm.crop)
                    }
                }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, marketFragment)
                    .addToBackStack(null)
                    .commit()
            } else if (farm == null) {
                Toast.makeText(context, "Please select a plot first.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // === NEW FUNCTION: Reliable Dialog Selection for Plot ===
    private fun showPlotSelectionDialog() {
        if (farmList.isEmpty()) {
            Toast.makeText(requireContext(), "No farm plots found to select.", Toast.LENGTH_SHORT).show()
            return
        }

        val farmNames = farmList.map { it.name }.toTypedArray()

        // Find the index of the currently selected plot
        val checkedItemIndex = farmList.indexOfFirst { it.id == selectedFarm?.id }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.hint_select_farm))
            .setSingleChoiceItems(farmNames, checkedItemIndex) { dialog, which ->

                // User selected an item
                val selectedFarmName = farmNames[which]
                val newFarm = farmList.find { it.name == selectedFarmName }

                if (newFarm != null) {
                    if (selectedFarm?.id != newFarm.id) {
                        selectedFarm = newFarm
                        // 1. Update the UI input field instantly
                        actFarmSelector.setText(newFarm.name, false)
                        // 2. Update location display text
                        tvYieldLocation.text = "Yield Analysis for: ${newFarm.name} (${newFarm.crop})"
                        // 3. Trigger full data load for the new farm (which checks cache/Firestore)
                        loadYieldData()
                    }
                }
                dialog.dismiss() // Close the dialog immediately upon selection
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    // ========================================================


    override fun onResume() {
        super.onResume()
        // Check worker status and update UI if necessary
        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData(YieldPredictionWorker.WORK_TAG)
            .observe(viewLifecycleOwner) { workInfos ->
                val running = workInfos.any { it.state.isFinished.not() }
                if (running) {
                    setLoadingState()
                } else if (btnRunAnalysis.isEnabled == false) {
                    // Only reload if the button was previously disabled (i.e., work just finished)
                    loadYieldData()
                    setReadyState()
                }
            }
    }

    private fun setLoadingState() {
        btnRunAnalysis.text = getString(R.string.yield_analysis_running)
        btnRunAnalysis.isEnabled = false
        btnGoToMarket.isEnabled = false // Disable market while running analysis
        tvCropYield.text = "..."
        tvGrowthStage.text = "..."
        tvSchedule.text = "..."
        tvAlert.text = "..."
    }

    private fun setReadyState() {
        btnRunAnalysis.text = getString(R.string.btn_run_yield_analysis)
        btnRunAnalysis.isEnabled = true
        btnGoToMarket.isEnabled = true
    }


    private fun runYieldAnalysis() {
        val farm = selectedFarm
        if (farm == null) {
            Toast.makeText(context, "Please select a farm first.", Toast.LENGTH_SHORT).show()
            return
        }

        // Ensure coordinates are not zero before running network-intensive task
        if (farm.lat == 0.0 && farm.lng == 0.0) {
            Toast.makeText(context, "Farm location coordinates are missing.", Toast.LENGTH_SHORT).show()
            return
        }

        setLoadingState()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = androidx.work.Data.Builder()
            .putDouble(YieldPredictionWorker.KEY_LAT, farm.lat)
            .putDouble(YieldPredictionWorker.KEY_LNG, farm.lng)
            .putString(YieldPredictionWorker.KEY_CROP, farm.crop)
            .putString(YieldPredictionWorker.KEY_FARM_ID, farm.id)
            .build()

        val workRequest = OneTimeWorkRequest.Builder(YieldPredictionWorker::class.java)
            .setConstraints(constraints)
            .setInputData(data) // Attach input data
            .addTag(YieldPredictionWorker.WORK_TAG)
            .build()

        // Use REPLACE policy to cancel any previous analysis and start the new one
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            YieldPredictionWorker.WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }


    private fun loadFarmListAndInitialData() {
        val uid = auth.currentUser?.uid ?: return
        setLoadingState()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userDoc = db.collection("users").document(uid).get().await()

                @Suppress("UNCHECKED_CAST")
                val farmData = userDoc.get("farm_data") as? Map<String, Any>

                val plotNames = farmData?.get("plots_names") as? List<String> ?: emptyList()
                val plotsCrops = farmData?.get("plots_crops") as? List<String> ?: emptyList()
                val coordsMapList = farmData?.get("plots_coordinates_flat") as? List<Map<String, Any>> ?: emptyList()

                val primaryLat = userDoc.getDouble("lat") ?: 0.0
                val primaryLng = userDoc.getDouble("lng") ?: 0.0
                val primaryCrop = userDoc.getString("crops_grown")?.split(",")?.firstOrNull()?.trim() ?: "Paddy"

                val currentFields = mutableListOf<FarmField>()
                var currentPlotCoords = mutableListOf<Map<String, Any>>()
                var attributeIndex = 0

                // 🔥 CORRECTED LOGIC: Iterate through the flat coordinates and reconstruct plots
                for (coordMap in coordsMapList) {
                    val isSeparator = coordMap["separator"] == true

                    if (isSeparator) {
                        // A separator marks the end of a plot
                        if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                            val anchorPoint = currentPlotCoords.first() // Use the first point as the anchor
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
                            attributeIndex++
                        }
                        currentPlotCoords = mutableListOf() // Start new plot
                    } else {
                        // Add non-separator points to the current plot's list
                        currentPlotCoords.add(coordMap)
                    }
                }

                // Handle the last plot (which may not have a trailing separator)
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


                // FALLBACK: If no fields were reconstructed, use the single primary anchor
                if (currentFields.isEmpty() && primaryLat != 0.0) {
                    currentFields.add(FarmField("primary", "Primary Farm Location", primaryCrop, primaryLat, primaryLng))
                }

                if (currentFields.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvYieldLocation.text = "No farm location set."
                        setReadyState()
                    }
                    return@launch
                }

                farmList = currentFields.toList()
                selectedFarm = farmList.first() // Default to Plot1

                withContext(Dispatchers.Main) {
                    setReadyState()

                    // FINAL UI UPDATE: Show the selected plot name and crop
                    actFarmSelector.setText(selectedFarm!!.name, false)
                    tvYieldLocation.text = "Yield Analysis for: ${selectedFarm!!.name} (${selectedFarm!!.crop})"

                    loadYieldData() // Load initial data for the default farm
                }

            } catch (e: Exception) {
                Log.e("YieldPredictionWorker", "Failed to load farm list: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    tvYieldLocation.text = "Error loading farm list."
                    setReadyState()
                }
            }
        }
    }

    private fun loadYieldData() {
        val uid = auth.currentUser?.uid ?: return
        val plotId = selectedFarm?.id ?: return // Get the selected plot's ID

        setLoadingState()
        tvCropYield.text = getString(R.string.yield_analysis_default)

        // Key is specific to the selected plot
        val yieldCacheKey = YIELD_CACHE_KEY_BASE + plotId

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 🔥 CRITICAL FIX: Fetch yield prediction using UID_PlotID
                val docId = "${uid}_${plotId}"
                Log.d("YieldPredictionFragment", "Fetching Yield Doc ID: $docId")

                val doc = db.collection(YieldPredictionWorker.YIELD_COLLECTION).document(docId).get().await()

                if (!isAdded) return@launch
                setReadyState()

                if (doc.exists()) {
                    // --- FIRESTORE SUCCESS: Robust Manual Mapping ---
                    val forecast = YieldForecast(
                        crop = doc.getString("crop") ?: "N/A",
                        expectedYield = doc.getDouble("expectedYield") ?: 0.0,
                        growthStage = doc.getString("growthStage") ?: "Unknown",
                        reductionAlert = doc.getString("reductionAlert") ?: "None",
                        optimalSchedule = doc.getString("optimalSchedule") ?: "No current advice.",
                        farm_id = doc.getString("farm_id") ?: "", // Manually pull farm_id
                        timestamp = doc.getLong("timestamp") ?: 0L
                    )

                    if (forecast.farm_id == plotId) {
                        // 1. Save to local cache
                        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                        prefs.edit().putString(yieldCacheKey, gson.toJson(forecast)).apply()

                        // 2. Display fresh data
                        displayYieldForecast(forecast)
                    } else {
                        // Doc exists but content mismatch (shouldn't happen with correct ID)
                        Log.w("YieldPredictionFragment", "Doc mismatch. Checking cache.")
                        loadYieldForecastFromCache(yieldCacheKey)
                    }

                } else {
                    // Firestore doc does not exist (first run or worker hasn't finished)
                    Log.w("YieldPredictionFragment", "Firestore Doc not found. Checking cache.")
                    loadYieldForecastFromCache(yieldCacheKey)
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                setReadyState()
                Log.e(YieldPredictionWorker.WORK_TAG, "Firestore/Network Load failed: ${e.message}")
                loadYieldForecastFromCache(yieldCacheKey, isNetworkError = true)
            }
        }
    }

    /**
     * Loads and displays yield forecast from local SharedPreferences cache.
     */
    private fun loadYieldForecastFromCache(cacheKey: String, isNetworkError: Boolean = false) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val cachedJson = prefs.getString(cacheKey, null)

        if (cachedJson != null) {
            try {
                // YieldForecast must be defined with all fields, including farm_id, for this Gson mapping to work.
                val cachedForecast = gson.fromJson(cachedJson, YieldForecast::class.java)
                displayYieldForecast(cachedForecast)

                val statusMessage = if (isNetworkError) " (OFFLINE CACHE)" else " (CACHED DATA)"
                // Append status message to the yield text only if it's not already showing the final value
                if (tvCropYield.text.toString().contains("Quintals")) {
                    tvCropYield.append(statusMessage)
                }
                Log.d("YieldPredictionFragment", "Yield data loaded from local cache.")

            } catch (e: Exception) {
                Log.e("YieldPredictionFragment", "Corrupt yield data found in cache.")
                showNoYieldDataMessage()
            }
        } else {
            showNoYieldDataMessage(isNetworkError)
        }
    }

    private fun displayYieldForecast(forecast: YieldForecast) {
        tvCropYield.text = HtmlCompat.fromHtml("<b>${forecast.crop}:</b> ${String.format("%.2f", forecast.expectedYield)} Quintals/Hectare", HtmlCompat.FROM_HTML_MODE_LEGACY)
        tvGrowthStage.text = forecast.growthStage
        tvSchedule.text = HtmlCompat.fromHtml(forecast.optimalSchedule, HtmlCompat.FROM_HTML_MODE_LEGACY)

        tvAlert.text = HtmlCompat.fromHtml(forecast.reductionAlert, HtmlCompat.FROM_HTML_MODE_LEGACY)
        val alertText = forecast.reductionAlert
        if (alertText.contains("reduction", ignoreCase = true) || alertText.contains("risk", ignoreCase = true)) {
            tvAlert.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_error))
        } else {
            tvAlert.setTextColor(ContextCompat.getColor(requireContext(), R.color.whatsapp_green))
        }
    }

    private fun showNoYieldDataMessage(isNetworkError: Boolean = false) {
        tvCropYield.text = getString(R.string.yield_analysis_default)
        tvGrowthStage.text = "N/A"
        tvSchedule.text = "N/A"
        tvAlert.text = if (isNetworkError) "Network failed. No cached forecast." else "No prediction available. Run Analysis."
        tvAlert.setTextColor(ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant))
    }
}