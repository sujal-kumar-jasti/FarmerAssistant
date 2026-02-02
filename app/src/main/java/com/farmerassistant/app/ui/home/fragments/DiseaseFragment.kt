package com.farmerassistant.app.ui.home.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.farmerassistant.app.R
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Date
import android.Manifest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.preference.PreferenceManager
import com.farmerassistant.app.workers.RegionalAlertWorker
import java.util.Locale
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.farmerassistant.app.ui.home.fragments.ReportAdapter
import java.util.concurrent.TimeUnit
import com.google.firebase.firestore.Query
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.withTimeoutOrNull
import android.location.Address
import android.widget.AutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import android.widget.ArrayAdapter


class DiseaseFragment : Fragment() {
    // DECLARATIONS
    private lateinit var ivPhoto: ImageView
    private lateinit var tvPrediction: TextView
    private lateinit var btnReportOutbreak: Button
    private lateinit var btnMitigation: Button
    private lateinit var btnSaveReport: Button
    private lateinit var rvOutbreaks: RecyclerView
    private lateinit var reportAdapter: ReportAdapter
    private lateinit var interpreter: Interpreter
    private var currentPhotoPath: String? = null
    private var currentPrediction: String = "N/A"
    private var currentSeverity: String = "Low"

    private lateinit var tilFarmSelector: TextInputLayout
    private lateinit var actFarmSelector: AutoCompleteTextView
    private var farmList: List<FarmField> = emptyList()
    private var selectedFarm: FarmField? = null

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "DiseaseFragment"

    // Location Data for Reporting/Filtering (updated dynamically by selectedFarm)
    private var userCropList: List<String> = emptyList()
    private var farmLat: Double = 0.0
    private var farmLng: Double = 0.0
    private var farmDistrict: String = "Unknown"

    private val REQ_GALLERY = 2
    private val INPUT_SIZE = 224
    private val NUM_CLASSES = 38
    private val MODEL_FILE = "plant_disease_model_38_classes.tflite"

    private val CAMERA_PERMISSION = arrayOf(Manifest.permission.CAMERA)

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleActivityResult(1, result.resultCode, result.data)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            dispatchTakePictureIntentInternal()
        } else {
            Toast.makeText(context, "Camera permission is required to capture photos.", Toast.LENGTH_LONG).show()
        }
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_disease, container, false)

        // --- BINDINGS ---
        ivPhoto = v.findViewById(R.id.ivPhoto)
        tvPrediction = v.findViewById(R.id.tvPrediction)
        btnReportOutbreak = v.findViewById(R.id.btnReportOutbreak)
        btnMitigation = v.findViewById(R.id.btnMitigation)
        btnSaveReport = v.findViewById(R.id.btnSaveReport)

        tilFarmSelector = v.findViewById(R.id.tilFarmSelector)
        actFarmSelector = v.findViewById(R.id.actFarmSelector)

        rvOutbreaks = v.findViewById(R.id.rvOutbreaks)
        reportAdapter = ReportAdapter(emptyList())
        rvOutbreaks.adapter = reportAdapter
        rvOutbreaks.layoutManager = LinearLayoutManager(context)

        // Initialize TFLite Interpreter
        try {
            interpreter = Interpreter(loadModelFile(requireContext(), MODEL_FILE))
            tvPrediction.text = "Prediction: Ready. Model [38 Classes, ${INPUT_SIZE}x${INPUT_SIZE}]."
        } catch (e: Exception) {
            tvPrediction.text = "ML Model Error! Check '$MODEL_FILE' in assets. ${e.message}"
            Log.e(TAG, "TFLite initialization failed: ${e.message}")
        }

        // --- EVENT LISTENERS ---
        v.findViewById<Button>(R.id.btnCamera).setOnClickListener {
            try { checkAndRequestCameraPermissions() } catch (e: Exception) { Log.e(TAG, "Camera click failed: ${e.message}", e) }
        }
        v.findViewById<Button>(R.id.btnGallery).setOnClickListener {
            try { dispatchGalleryIntent() } catch (e: Exception) { Log.e(TAG, "Gallery click failed: ${e.message}", e) }
        }
        btnSaveReport.setOnClickListener {
            try { savePredictionLocally() } catch (e: Exception) { Log.e(TAG, "Save click failed: ${e.message}", e) }
        }

        btnReportOutbreak.setOnClickListener {
            try { showReportOutbreakDialog() } catch (e: Exception) {
                Log.e(TAG, "Report Outbreak click failed: ${e.message}", e)
                Toast.makeText(context, "Report error: See logcat for details.", Toast.LENGTH_LONG).show()
            }
        }
        btnMitigation.setOnClickListener {
            try { launchChatbotForMitigation() } catch (e: Exception) {
                Log.e(TAG, "Mitigation click failed: ${e.message}", e)
                Toast.makeText(context, "Chatbot error: See logcat for details.", Toast.LENGTH_LONG).show()
            }
        }

        // --- PLOT SELECTOR LISTENER ---
        actFarmSelector.setOnClickListener {
            showPlotSelectionDialog()
        }
        // --- END PLOT SELECTOR LISTENER ---


        // Initial state
        btnReportOutbreak.isEnabled = true // Always enabled for manual reporting
        btnMitigation.isEnabled = false // Enabled only after a successful detection

        loadFarmerCropsAndLocation()

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore state if available
        savedInstanceState?.let {
            currentPrediction = it.getString("currentPrediction", "N/A")
            currentSeverity = it.getString("currentSeverity", "Low")
            currentPhotoPath = it.getString("currentPhotoPath", null)

            tvPrediction.text = "Prediction: $currentPrediction | Severity: $currentSeverity"
            btnMitigation.isEnabled = !(currentPrediction.contains("N/A") || currentPrediction.contains("healthy", ignoreCase = true))

            currentPhotoPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(path)
                    ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivPhoto.imageTintList = null
                    ivPhoto.setImageResource(0)
                    ivPhoto.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentPrediction", currentPrediction)
        outState.putString("currentSeverity", currentSeverity)
        outState.putString("currentPhotoPath", currentPhotoPath)
    }

    // --- DIALOG FOR PLOT SELECTION ---
    private fun showPlotSelectionDialog() {
        if (farmList.isEmpty()) {
            Toast.makeText(requireContext(), "No farm plots found.", Toast.LENGTH_SHORT).show()
            return
        }

        val farmNames = farmList.map { it.name }.toTypedArray()
        val checkedItemIndex = farmList.indexOfFirst { it.id == selectedFarm?.id }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hint_select_farm)
            .setSingleChoiceItems(farmNames, checkedItemIndex) { dialog, which ->

                val selectedFarmName = farmNames[which]
                val newFarm = farmList.find { it.name == selectedFarmName }

                if (newFarm != null && selectedFarm?.id != newFarm.id) {
                    selectedFarm = newFarm
                    actFarmSelector.setText(newFarm.name, false)

                    // CRITICAL: Update all location-dependent variables
                    updateFarmContext(newFarm)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    // --- END DIALOG ---

    // --- CONTEXT UPDATER ---
    private fun updateFarmContext(farm: FarmField) {
        // 1. Set the new coordinates
        farmLat = farm.lat
        farmLng = farm.lng

        // 2. Geocode the location (asynchronously)
        lifecycleScope.launch {
            farmDistrict = getDistrictFromCoordinates(farmLat, farmLng)

            // 3. Update SharedPreferences with new district (CRITICAL for Chatbot/Workers)
            if (farmDistrict != "Unknown") {
                // We save the full, granular location string to preferences
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                prefs.edit().putString("user_district", farmDistrict).apply()
            }

            // 4. Reload regional outbreaks for the new district
            fetchRegionalOutbreaks()

            // 5. Update UI (optional, for visibility)
            Toast.makeText(context, "Context set to ${farm.name} (${farmDistrict})", Toast.LENGTH_SHORT).show()
        }
    }
    // --- END CONTEXT UPDATER ---


    /**
     * Attempts to geocode the coordinates to the most granular administrative area,
     * prioritizing Sub-Admin Area (District) and then combining with Locality (Town/Village).
     */
    private suspend fun getDistrictFromCoordinates(lat: Double, lng: Double): String {
        if (!isAdded) return "Unknown"
        if (lat == 0.0 && lng == 0.0) return "Unknown"

        // Ensure geocoding runs on the IO dispatcher
        return withContext(Dispatchers.IO) {
            try {
                val addressList = withTimeoutOrNull(5000L) { // 5 second timeout
                    @Suppress("DEPRECATION")
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
                    // Fetch multiple results for robust checking
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocation(lat, lng, 3)
                    } else {
                        geocoder.getFromLocation(lat, lng, 3)
                    }
                }

                if (addressList.isNullOrEmpty()) return@withContext "Unknown"

                val bestAddress = addressList.first()
                val subAdmin = bestAddress.subAdminArea
                val locality = bestAddress.locality
                val subLocality = bestAddress.subLocality

                // Combine the most granular non-null elements for a specific location string
                // Example: SubLocality, Locality, SubAdminArea
                val parts = mutableListOf<String>()

                // 1. Highest priority: Sub-Locality (Village/Neighborhood)
                if (!subLocality.isNullOrEmpty()) {
                    parts.add(subLocality)
                }
                // 2. Medium priority: Locality (City/Town) - only add if not already covered by subLocality
                if (!locality.isNullOrEmpty() && locality != subLocality) {
                    parts.add(locality)
                }
                // 3. Lower priority: Sub-Admin Area (District) - essential for regional context
                if (!subAdmin.isNullOrEmpty()) {
                    parts.add(subAdmin)
                }

                val granularLocation = parts.distinct().joinToString(", ")

                if (granularLocation.isNotEmpty()) {
                    Log.d(TAG, "Geocoded to Granular Location: $granularLocation")
                    granularLocation
                } else {
                    // Fallback to Admin Area if nothing else is found
                    val adminArea = bestAddress.adminArea
                    if (!adminArea.isNullOrEmpty()) {
                        Log.d(TAG, "Geocoded to Admin Area: $adminArea")
                        adminArea
                    } else {
                        "Unknown"
                    }
                }

            } catch (e: TimeoutException) {
                Log.e(TAG, "Geocoding failed due to timeout.")
                "Unknown"
            } catch (e: IOException) {
                Log.e(TAG, "Geocoding failed due to network/service: ${e.message}.")
                "Unknown"
            } catch (e: Exception) {
                Log.e(TAG, "Geocoding failed: ${e.message}")
                "Unknown"
            }
        }
    }

    // --- PLOT LIST LOADING (Adapted from Climate/Yield Fragments) ---
    private fun loadFarmerCropsAndLocation() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            val uniqueCrops = mutableSetOf<String>()

            try {
                val userDoc = db.collection("users").document(uid).get().await()

                @Suppress("UNCHECKED_CAST")
                val farmData = userDoc.get("farm_data") as? Map<String, Any>

                val plotNames = farmData?.get("plots_names") as? List<String> ?: emptyList()
                val plotsCrops = farmData?.get("plots_crops") as? List<String> ?: emptyList()
                val coordsMapList = farmData?.get("plots_coordinates_flat") as? List<Map<String, Any>> ?: emptyList()

                val primaryLat = farmData?.get("anchor_lat") as? Double ?: userDoc.getDouble("lat") ?: 0.0
                val primaryLng = farmData?.get("anchor_lng") as? Double ?: userDoc.getDouble("lng") ?: 0.0
                val primaryCrop = userDoc.getString("crops_grown")?.split(",")?.firstOrNull()?.trim() ?: "Paddy"
                val userDistrictFallback = userDoc.getString("district") ?: "Unknown"

                val currentFields = mutableListOf<FarmField>()
                var currentPlotCoords = mutableListOf<Map<String, Any>>()
                var attributeIndex = 0

                // Plot Reconstruction Logic
                for (coordMap in coordsMapList) {
                    val isSeparator = coordMap["separator"] == true
                    if (isSeparator) {
                        if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                            val anchorPoint = currentPlotCoords.first()
                            val name = plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                            val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                            currentFields.add(FarmField(name.replace(" ", "_"), name, crop,
                                anchorPoint["latitude"] as? Double ?: 0.0, anchorPoint["longitude"] as? Double ?: 0.0, userDistrictFallback))
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

                    currentFields.add(FarmField(name.replace(" ", "_"), name, crop,
                        anchorPoint["latitude"] as? Double ?: 0.0, anchorPoint["longitude"] as? Double ?: 0.0, userDistrictFallback))
                }

                // Fallback to primary location if no plots found
                if (currentFields.isEmpty() && primaryLat != 0.0) {
                    currentFields.add(FarmField("primary", "Primary Farm Location", primaryCrop, primaryLat, primaryLng, userDistrictFallback))
                }

                farmList = currentFields.toList()
                selectedFarm = farmList.firstOrNull()

                // 2. Get Comprehensive Crop List (for dialog options)
                userDoc.getString("crops_grown")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { uniqueCrops.add(it) }
                farmData?.let { (it["plots_crops"] as? List<String>)?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { uniqueCrops.add(it) } }
                userCropList = uniqueCrops.toList().sorted()
                if (userCropList.isEmpty()) userCropList = listOf("Unknown Crop")

                // 3. Finalize UI and Context on Main thread
                withContext(Dispatchers.Main) {
                    if (selectedFarm != null) {
                        actFarmSelector.setText(selectedFarm!!.name, false)
                        updateFarmContext(selectedFarm!!) // Use the helper to initialize location/reports
                    } else {
                        actFarmSelector.setText("No Plots Found", false)
                        farmDistrict = "Unknown"
                        fetchRegionalOutbreaks() // Fetch nothing or default if context fails
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load farm context or geocode: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error loading farm data. Using defaults.", Toast.LENGTH_LONG).show()
                    farmDistrict = "Unknown"
                    fetchRegionalOutbreaks()
                }
            }
        }
    }
    // --- END PLOT LIST LOADING ---


    private fun fetchRegionalOutbreaks() {
        if (farmDistrict == "Unknown") {
            Log.w(TAG, "Cannot fetch outbreaks: District is Unknown. Check user location/geocoding.")
            // Display a message to the user
            reportAdapter.updateReports(
                listOf(RegionalReport(disease = "Cannot fetch outbreaks: Farm location missing or unknown district.", crop = ""))
            )
            return
        }

        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7) // Reports from the last 7 days

        // Filter based on the derived farmDistrict (now a granular location string)
        db.collection(RegionalAlertWorker.REGIONAL_REPORTS_COLLECTION)
            .whereEqualTo("district", farmDistrict)
            .whereGreaterThan("timestamp", cutoffTime)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!isAdded) return@addOnSuccessListener

                val rawReportsList = querySnapshot.documents.mapNotNull { document ->
                    document.toObject(RegionalReport::class.java)
                }

                val aggregatedReports = aggregateReports(rawReportsList)

                if (aggregatedReports.isNotEmpty()) {
                    reportAdapter.updateReports(aggregatedReports)
                } else {
                    reportAdapter.updateReports(
                        listOf(RegionalReport(disease = "No recent outbreaks reported in $farmDistrict.", crop = ""))
                    )
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching regional outbreaks: ${e.message}", e)
                if (isAdded) {
                    Toast.makeText(context, "Failed to load regional reports.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun aggregateReports(rawReports: List<RegionalReport>): List<RegionalReport> {
        val groupedReports = rawReports.groupBy { it.crop to it.disease }

        return groupedReports.map { (key, reports) ->
            val (crop, disease) = key
            val totalCount = reports.size

            val severityCounts = reports.groupingBy { it.severity }.eachCount()
            val majoritySeverity = severityCounts.maxByOrNull { it.value }?.key ?: "Low"

            val latestTimestamp = reports.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()

            RegionalReport(
                crop = crop,
                disease = "$disease ($totalCount Reports)",
                severity = majoritySeverity,
                timestamp = latestTimestamp
            )
        }.sortedByDescending { it.timestamp }
    }


    // --- showReportOutbreakDialog (FIXED findViewById error and hint logic) ---
    private fun showReportOutbreakDialog() {
        val isManualReport = currentPrediction.contains("N/A") || currentPrediction.contains("healthy", ignoreCase = true)

        if (farmLat == 0.0 || farmLng == 0.0 || farmDistrict == "Unknown") {
            Toast.makeText(context, "Farm location or district is unknown. Cannot report regionally.", Toast.LENGTH_LONG).show()
            return
        }

        val parts = currentPrediction.split(" (")
        val cropDisease = parts.firstOrNull()?.split("___") ?: listOf("", "")

        val defaultCrop = if (!isManualReport) cropDisease.getOrElse(0) { "Unknown Crop" } else userCropList.firstOrNull() ?: "Unknown Crop"
        val predictedDiseaseName = if (!isManualReport) cropDisease.getOrElse(1) { "Unknown Disease" } else ""

        val severityOptions = arrayOf("High", "Medium", "Low")

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_report_outbreak, null)

        val actCrop = dialogView.findViewById<AutoCompleteTextView>(R.id.actCrop)
        val etDiseaseConfirmed = dialogView.findViewById<TextInputEditText>(R.id.etDiseaseConfirmed)
        val actSeverity = dialogView.findViewById<AutoCompleteTextView>(R.id.actSeverity)
        // Note: We avoid findViewById for R.id.tilDiseaseConfirmed to prevent runtime errors if it's missing.

        etDiseaseConfirmed.setText(predictedDiseaseName)
        etDiseaseConfirmed.isEnabled = true
        etDiseaseConfirmed.isFocusable = true
        etDiseaseConfirmed.isFocusableInTouchMode = true

        val cropAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, userCropList)
        actCrop.setAdapter(cropAdapter)
        actCrop.setText(defaultCrop, false)

        val severityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, severityOptions)
        actSeverity.setAdapter(severityAdapter)
        actSeverity.setText(currentSeverity, false)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_report_outbreak)
            .setView(dialogView)
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.dialog_report) { dialog, _ ->
                val selectedCrop = actCrop.text.toString().trim()
                val confirmedDisease = etDiseaseConfirmed.text.toString().trim()
                val finalSeverity = actSeverity.text.toString().trim()

                if (selectedCrop.isNotEmpty() && confirmedDisease.isNotEmpty()) {
                    submitRegionalReport(selectedCrop, confirmedDisease, finalSeverity, isManualReport)
                } else {
                    Toast.makeText(context, "Crop and Disease fields are required.", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Submission uses farmLat/Lng and farmDistrict, ensuring the report is filed for the correct location.
     */
    private fun submitRegionalReport(crop: String, disease: String, severity: String, isManual: Boolean = false) {
        val uid = auth.currentUser?.uid ?: return

        val reportData = hashMapOf(
            "reporterUid" to uid,
            "crop" to crop,
            "disease" to disease,
            "severity" to severity,
            "lat" to farmLat,
            "lng" to farmLng,
            "district" to farmDistrict, // This now holds the granular location string
            "timestamp" to System.currentTimeMillis(),
            "reported_by_user" to isManual
        )

        db.collection(RegionalAlertWorker.REGIONAL_REPORTS_COLLECTION)
            .add(reportData)
            .addOnSuccessListener {
                if (isAdded) {
                    Toast.makeText(
                        context,
                        "Outbreak reported successfully to the ${farmDistrict} region. Thank you!",
                        Toast.LENGTH_LONG
                    ).show()
                    // The button remains enabled for subsequent manual reports
                    fetchRegionalOutbreaks()
                }
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Toast.makeText(context, "Error reporting outbreak: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Report failed: ${e.message}")
                }
            }
    }


    // --- LAUNCH CHATBOT FOR MITIGATION (UPDATED for contextual prompt) ---
    private fun launchChatbotForMitigation() {
        if (currentPrediction.contains("N/A") || currentPrediction.contains("healthy", ignoreCase = true)) {
            Toast.makeText(context, "Please run a detection for a specific disease first.", Toast.LENGTH_LONG).show()
            return
        }

        val parts = currentPrediction.split(" (")
        val disease = parts.firstOrNull() ?: currentPrediction

        // 🔥 CRITICAL: Embed location details in the prompt
        val prompt = "Provide step-by-step mitigation advice for the disease: $disease. The farm is in the ${farmDistrict} area. The crop is ${selectedFarm?.crop ?: userCropList.firstOrNull()}. Keep the steps actionable for an Indian farmer."

        if (isAdded && parentFragmentManager != null) {
            // Assuming ChatbotFragment class exists in the same package or is imported
            val chatbotFragment = ChatbotFragment().apply {
                arguments = Bundle().apply {
                    putString("initial_prompt", prompt)
                }
            }

            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, chatbotFragment)
                .addToBackStack(null)
                .commit()
        }
    }
    // --- END CHATBOT LAUNCH ---


    // --- UNCHANGED HELPER FUNCTIONS (Placeholder implementations must match your file) ---
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun runDetection(bitmap: Bitmap) {
        tvPrediction.text = "Predicting..."

        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixelValue in intValues) {
            inputBuffer.putFloat(((pixelValue shr 16 and 0xFF) / 255.0f)) // Red
            inputBuffer.putFloat(((pixelValue shr 8 and 0xFF) / 255.0f))  // Green
            inputBuffer.putFloat(((pixelValue and 0xFF) / 255.0f))       // Blue
        }

        val output: Array<FloatArray> = Array(1) { FloatArray(NUM_CLASSES) }

        inputBuffer.rewind()
        interpreter.run(inputBuffer, output)

        val labels = listOf(
            "Apple___Apple_scab", "Apple___Black_rot", "Apple___Cedar_apple_rust", "Apple___healthy",
            "Blueberry___healthy", "Cherry_(including_sour)___Powdery_mildew",
            "Cherry_(including_sour)___healthy", "Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot",
            "Corn_(maize)___Common_rust_", "Corn_(maize)___Northern_Leaf_Blight", "Corn_(maize)___healthy",
            "Grape___Black_rot", "Grape___Esca_(Black_Measles)", "Grape___Leaf_blight_(Isariopsis_Leaf_Spot)",
            "Grape___healthy", "Orange___Haunglongbing_(Citrus_greening)", "Peach___Bacterial_spot",
            "Peach___healthy", "Pepper,_bell___Bacterial_spot", "Pepper,_bell___healthy",
            "Potato___Early_blight", "Potato___Late_blight", "Potato___healthy", "Raspberry___healthy",
            "Soybean___healthy", "Squash___Powdery_mildew", "Strawberry___Leaf_scorch",
            "Strawberry___healthy", "Tomato___Bacterial_spot", "Tomato___Early_blight",
            "Tomato___Late_blight", "Tomato___Leaf_Mold", "Tomato___Septoria_leaf_spot",
            "Tomato___Spider_mites Two-spotted_spider_mite", "Tomato___Target_Spot",
            "Tomato___Tomato_Yellow_Leaf_Curl_Virus", "Tomato___Tomato_mosaic_virus", "Tomato___healthy"
        )

        val maxIdx = output[0].indices.maxByOrNull { output[0][it] } ?: 0
        val confidence = output[0][maxIdx] * 100
        val label = labels[maxIdx]

        currentSeverity = when {
            label.contains("healthy", ignoreCase = true) -> "Low"
            label.contains("virus", ignoreCase = true) || label.contains("rot", ignoreCase = true) -> "High"
            confidence < 90 -> "Medium"
            else -> "Low"
        }

        val result = "$label (${"%.2f".format(confidence)}%)"
        tvPrediction.text = "Prediction: $result | Severity: $currentSeverity"
        currentPrediction = result

        val isHealthy = label.contains("healthy", ignoreCase = true)

        btnMitigation.isEnabled = !isHealthy // Mitigation button remains conditional

        if (!isHealthy) {
            Toast.makeText(context, "Disease detected! Mitigation button enabled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestCameraPermissions() {
        val cameraGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            dispatchTakePictureIntentInternal()
        } else {
            permissionLauncher.launch(CAMERA_PERMISSION)
        }
    }

    private fun dispatchTakePictureIntentInternal() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(requireActivity().packageManager) == null) {
            Toast.makeText(context, "No camera app found.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val photoFile: File? = createImageFile()
            photoFile?.also {
                val photoURI: Uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    it
                )
                takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                cameraLauncher.launch(takePictureIntent)
            }
        } catch (ex: IOException) {
            Toast.makeText(context, "Error creating file: ${ex.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "File creation failed: ${ex.message}")
        } catch (ex: Exception) {
            Toast.makeText(context, "Cannot open camera. Check FileProvider setup.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Camera dispatch failed: ${ex.message}")
        }
    }

    private fun dispatchGalleryIntent() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_GALLERY)
    }

    private fun createImageFile(): File {
        val storageDir: File? = requireActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("CROP_${System.currentTimeMillis()}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return

        val bitmap: Bitmap? = when (requestCode) {
            1 -> {
                currentPhotoPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) BitmapFactory.decodeFile(path) else null
                }
            }
            REQ_GALLERY -> {
                val uri = data?.data ?: return
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
            }
            else -> null
        }

        if (bitmap != null) {
            ivPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
            ivPhoto.imageTintList = null
            ivPhoto.setImageResource(0)
            ivPhoto.setImageBitmap(bitmap)
            runDetection(bitmap)
        } else {
            Toast.makeText(context, "Failed to load image. If using gallery, ensure permission is granted.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Bitmap was null after capture/selection.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_GALLERY) {
            handleActivityResult(requestCode, resultCode, data)
        }
    }

    private fun savePredictionLocally() {
        if (currentPrediction == "N/A" || currentPrediction.contains("Predicting")) {
            Toast.makeText(context, "Please run prediction first.", Toast.LENGTH_LONG).show()
            return
        }
        val searches = requireActivity().getSharedPreferences("DiseaseHistory", Context.MODE_PRIVATE)
        val editor = searches.edit()
        val key = Date().time.toString()
        val valueToSave = "Time: ${android.text.format.DateFormat.format("hh:mm a", Date())} | Severity: $currentSeverity | $currentPrediction"
        editor.putString(key, valueToSave)
        if (editor.commit()) {
            Toast.makeText(context, "Prediction saved to local device history!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Error saving local data.", Toast.LENGTH_SHORT).show()
        }
    }
}
