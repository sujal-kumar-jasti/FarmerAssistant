package com.farmerassistant.app.ui.home.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.farmerassistant.app.R
import com.farmerassistant.app.ui.auth.LoginActivity
import com.farmerassistant.app.ui.auth.LanguageSelectActivity
import com.farmerassistant.app.ui.maps.MapSelectActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.util.Locale

// PlotDetail must include the name for map rendering
data class PlotDetail(
    val name: String,
    val crop: String,
    val irrigationType: String,
    val soilType: String,
    val coordinates: List<LatLng>
)

data class Alert(val lat: Double, val lng: Double, val type: String, val message: String, val severity: String = "Medium")

class AccountFragment : Fragment() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var googleSignInClient: GoogleSignInClient

    private val TAG = "AccountFragment"
    private val MAP_SNAPSHOT_FILENAME = "farm_map_snapshot.png" // Added constant for filename

    private var googleMapInstance: GoogleMap? = null
    private var isMapLoaded = false // Flag to check if GoogleMap is initialized

    private lateinit var tvLocalHistory: TextView
    private lateinit var btnChangeLocation: Button
    private lateinit var tvFarmLocation: TextView
    private lateinit var btnMapToggle: FloatingActionButton
    private lateinit var btnMapZoomIn: Button
    private lateinit var btnMapZoomOut: Button
    private lateinit var ivMapSnapshot: ImageView // Added ImageView for snapshot fallback
    private lateinit var mapFragmentContainer: View // Reference to the map fragment's container view

    private lateinit var btnViewAnalytics: Button

    private val PLOT_SEPARATOR = GeoPoint(999.0, 999.0)

    private var farmPlotDetailsList: List<PlotDetail>? = null
    private var farmPlotNamesList: List<String>? = null
    private var plotCount: Int = 0
    private var farmAlerts: List<Alert> = emptyList()

    private var farmerName: String = "N/A"
    private var farmerAddress: String = "N/A"

    private val PLOTS_DOCUMENT_PATH = "farm_data"

    private val mapActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Data is sync'd by MapSelectActivity, we just need to reload everything
            loadProfileData()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_account, container, false)

        // BINDINGS
        tvLocalHistory = v.findViewById(R.id.tvLocalHistory)
        btnChangeLocation = v.findViewById(R.id.btnChangeLocation)
        tvFarmLocation = v.findViewById(R.id.tvFarmLocation)
        btnMapToggle = v.findViewById(R.id.btnMapToggle)
        btnMapZoomIn = v.findViewById(R.id.btnMapZoomIn)
        btnMapZoomOut = v.findViewById(R.id.btnMapZoomOut)
        ivMapSnapshot = v.findViewById(R.id.ivMapSnapshot) // Binding the new ImageView
        mapFragmentContainer = v.findViewById(R.id.accountMapFragmentContainer)
        btnViewAnalytics = v.findViewById(R.id.btnViewAnalytics)// Get map container reference

        // Initialize the map fragment
        val mapFragment = childFragmentManager.findFragmentById(R.id.accountMapFragmentContainer) as? SupportMapFragment

        // Setup Map Lifecycle and Render Logic
        mapFragment?.getMapAsync { googleMap ->
            googleMapInstance = googleMap
            isMapLoaded = true // Map is now initialized
            googleMap.mapType = GoogleMap.MAP_TYPE_SATELLITE

            // Disable default zoom controls since we have custom buttons
            googleMap.uiSettings.isZoomControlsEnabled = false
            googleMap.uiSettings.isScrollGesturesEnabled = true
            googleMap.uiSettings.isZoomGesturesEnabled = true
            googleMap.uiSettings.isTiltGesturesEnabled = true
            googleMap.uiSettings.isRotateGesturesEnabled = true

            // CRITICAL FIX: Setup proper touch handling
            mapFragment.view?.let { mapContainerView ->
                val parentScrollView = v.findViewById<ScrollView>(R.id.accountFragmentScrollView)
                setupEnhancedTouchLock(parentScrollView, mapContainerView)
            }

            // If the map loads successfully, hide the snapshot image
            ivMapSnapshot.visibility = View.GONE
            mapFragmentContainer.visibility = View.VISIBLE

            // Re-call setupMap now that googleMapInstance is ready
            loadProfileData()
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

        // 🔥 IMPORTANT: Load snapshot immediately before attempting to load map data
        loadCachedSnapshot()

        // Load data, which will call setupMap once the map is ready or load the fallback
        loadProfileData()
        loadLocalHistory()

        // Toggle Listener
        btnMapToggle.setOnClickListener { toggleMapType() }

        // Zoom Button Listeners with haptic feedback
        btnMapZoomIn.setOnClickListener {
            googleMapInstance?.animateCamera(CameraUpdateFactory.zoomIn())
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        btnMapZoomOut.setOnClickListener {
            googleMapInstance?.animateCamera(CameraUpdateFactory.zoomOut())
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }

        v.findViewById<Button>(R.id.btnChangeLanguage).setOnClickListener {
            val intent = Intent(requireContext(), LanguageSelectActivity::class.java).apply {
                putExtra("from_account", true)
            }
            startActivity(intent)
        }

        v.findViewById<Button>(R.id.logoutBtn).setOnClickListener { performLogout() }

        btnChangeLocation.setOnClickListener {
            launchMapActivityForPolygon()
        }
        btnViewAnalytics.setOnClickListener {
            if (isAdded && parentFragmentManager != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, AnalyticsFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }


        return v
    }

    /**
     * 🔥 NEW: Loads the saved map snapshot from the cache directory and displays it
     * in the ImageView as a fallback.
     */
    private fun loadCachedSnapshot() {
        val file = File(requireContext().cacheDir, MAP_SNAPSHOT_FILENAME)
        if (file.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ivMapSnapshot.setImageBitmap(bitmap)
                ivMapSnapshot.visibility = View.VISIBLE // Show the snapshot
                mapFragmentContainer.visibility = View.GONE // Hide the Google Map fragment initially
                Log.d(TAG, "Loaded cached map snapshot.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cached map snapshot: ${e.message}")
            }
        } else {
            ivMapSnapshot.visibility = View.GONE
        }
    }


    private fun setupEnhancedTouchLock(parentScrollView: ScrollView?, mapContainerView: View) {
        if (parentScrollView == null) {
            Log.w(TAG, "ScrollView not found. Skipping touch lock.")
            return
        }

        mapContainerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parentScrollView.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parentScrollView.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
        mapContainerView.isClickable = true
    }

    private fun toggleMapType() {
        val map = googleMapInstance ?: return

        if (map.mapType == GoogleMap.MAP_TYPE_SATELLITE) {
            map.mapType = GoogleMap.MAP_TYPE_NORMAL
            Toast.makeText(requireContext(), "Switched to Street View", Toast.LENGTH_SHORT).show()
        } else {
            map.mapType = GoogleMap.MAP_TYPE_SATELLITE
            Toast.makeText(requireContext(), "Switched to Satellite View", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDerivedLocationData() {
        plotCount = farmPlotDetailsList?.size ?: 0

        val firstPlotAnchor = farmPlotDetailsList?.firstOrNull()?.coordinates?.firstOrNull()

        // 🔥 CRITICAL FIX: Only display plot count and rely on plot data for location status
        if (plotCount > 0) {
            tvFarmLocation.text = getString(R.string.message_plots_set, plotCount) +
                    getString(R.string.message_primary_location,
                        firstPlotAnchor?.latitude?.format(4) ?: "--",
                        firstPlotAnchor?.longitude?.format(4) ?: "--")
        } else {
            tvFarmLocation.text = getString(R.string.message_location_not_set)
        }
    }

    private fun loadProfileData() {
        val uid = auth.currentUser?.uid ?: return

        val userRef = db.collection("users").document(uid)

        userRef.get().addOnSuccessListener { profileDoc ->
            if (!isAdded) {
                Log.w(TAG, "Profile Firestore detached, skipping processing.")
                return@addOnSuccessListener
            }

            farmerName = profileDoc.getString("name") ?: "N/A"
            farmerAddress = profileDoc.getString("address") ?: "N/A"

            @Suppress("UNCHECKED_CAST")
            val farmData = profileDoc.get(PLOTS_DOCUMENT_PATH) as? Map<String, Any>

            var anchorLat = profileDoc.getDouble("lat") ?: 0.0
            var anchorLng = profileDoc.getDouble("lng") ?: 0.0


            if (farmData != null) {
                // Retrieve plot data lists
                @Suppress("UNCHECKED_CAST")
                val plotNames = farmData["plots_names"] as? List<String>

                @Suppress("UNCHECKED_CAST")
                val crops = farmData["plots_crops"] as? List<String>
                @Suppress("UNCHECKED_CAST")
                val irrigations = farmData["plots_irrigations"] as? List<String>
                @Suppress("UNCHECKED_CAST")
                val soils = farmData["plots_soil"] as? List<String>
                @Suppress("UNCHECKED_CAST")
                val coordMaps = farmData["plots_coordinates_flat"] as? List<Map<String, Any>>

                anchorLat = farmData["anchor_lat"] as? Double ?: anchorLat
                anchorLng = farmData["anchor_lng"] as? Double ?: anchorLng


                if (crops != null && soils != null && irrigations != null && coordMaps != null && plotNames != null && plotNames.size == crops.size) {
                    // Reconstruct plot details, including the name
                    farmPlotDetailsList = reconstructPlotDetails(coordMaps, crops, irrigations, soils, plotNames)
                    farmPlotNamesList = plotNames
                } else {
                    farmPlotDetailsList = emptyList()
                    farmPlotNamesList = emptyList()
                    Log.w(TAG, "Farm data found but incomplete (missing names/crops/coords or counts mismatch). Displaying anchor only.")
                }
            } else {
                farmPlotDetailsList = emptyList()
                farmPlotNamesList = emptyList()
            }

            view?.findViewById<TextView>(R.id.accName)?.text = getString(R.string.label_farmer_name, farmerName)
            view?.findViewById<TextView>(R.id.accAddr)?.text = getString(R.string.label_address, farmerAddress)
            updateDerivedLocationData()

            // Fetch secondary data (soil and alerts)
            fetchSecondaryData(uid)

        }.addOnFailureListener {
            if (isAdded) {
                Toast.makeText(context, R.string.error_loading_profile_data, Toast.LENGTH_SHORT).show()
                // If data load fails, only use the snapshot if map isn't ready
                if (!isMapLoaded) {
                    ivMapSnapshot.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * UPDATED: Fetches alerts by checking plot-specific worker documents for every known plot.
     * This function now fully replaces the old, unreliable single-document checks.
     */
    private fun fetchSecondaryData(uid: String) {
        val allAlerts = mutableListOf<Alert>()
        val plotsToCheck = farmPlotDetailsList.orEmpty()
        val alertsTask = db.collection("alerts").document(uid).get() // For general regional alerts

        lifecycleScope.launch(Dispatchers.IO) {

            // --- 1. FETCH SOIL/YIELD/CLIMATE ALERTS PER PLOT ---
            for (plotDetail in plotsToCheck) {
                val plotId = plotDetail.name.replace(" ", "_")
                val lat = plotDetail.coordinates.firstOrNull()?.latitude ?: 0.0
                val lng = plotDetail.coordinates.firstOrNull()?.longitude ?: 0.0

                if (lat == 0.0 || lng == 0.0) continue

                val soilDocId = "${uid}_${plotId}"
                val yieldDocId = "${uid}_${plotId}"
                val climateDocId = "${uid}_${plotId}"

                try {
                    val soilDoc = db.collection("soil_health").document(soilDocId).get().await()
                    val yieldDoc = db.collection("yield_predictions").document(yieldDocId).get().await()
                    val climateDoc = db.collection("climate_data").document(climateDocId).get().await()

                    // 1A. Check SOIL HEALTH ALERT
                    val soilStatus = soilDoc.getString("status")
                    if (soilDoc.exists() && soilStatus != "Optimal") {
                        val severity = when {
                            soilStatus?.contains("Deficient", ignoreCase = true) == true || soilStatus?.contains("Excess", ignoreCase = true) == true -> "High"
                            soilStatus?.contains("Risk", ignoreCase = true) == true -> "Medium"
                            else -> "Low"
                        }
                        allAlerts.add(Alert(lat, lng, "Soil Alert: ${plotDetail.name}", "Status: $soilStatus", severity))
                    }

                    // 1B. Check YIELD REDUCTION ALERT
                    val yieldAlert = yieldDoc.getString("reductionAlert")
                    if (yieldDoc.exists() && yieldAlert != "None" && !yieldAlert.isNullOrEmpty()) {
                        val severity = if (yieldAlert.contains("reduction", ignoreCase = true)) "High" else "Medium"
                        allAlerts.add(Alert(lat, lng, "Yield Risk: ${plotDetail.name}", yieldAlert, severity))
                    }

                    // 1C. Check CLIMATE ALERT (using worker-saved metrics)
                    val tempAvg = climateDoc.getDouble("temp_avg") ?: 0.0
                    if (climateDoc.exists() && tempAvg > 35.0) {
                        allAlerts.add(Alert(lat, lng, "Climate Alert: ${plotDetail.name}", "Heat Stress Detected", "High"))
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch worker data for plot $plotId: ${e.message}")
                }
            }
            // --- END PLOT SPECIFIC FETCH ---


            // 2. FETCH GENERAL REGIONAL ALERTS (Hotspots/Regional)
            try {
                val alertsResult = alertsTask.await()
                if (alertsResult.exists()) {
                    @Suppress("UNCHECKED_CAST")
                    val regionalData = alertsResult.get("regional_outbreaks") as? List<Map<String, Any>>

                    regionalData.orEmpty().forEach { map ->
                        (map["lat"] as? Double)?.let { lat_alert ->
                            (map["lng"] as? Double)?.let { lng_alert ->
                                allAlerts.add(
                                    Alert(lat_alert, lng_alert, map["type"] as? String ?: "Regional Alert",
                                        map["message"] as? String ?: "Outbreak detected.", map["severity"] as? String ?: "Medium")
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch general alerts: ${e.message}")
            }

            // 3. Finalize Map Setup on Main Thread
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    farmAlerts = allAlerts.toList()
                    setupMap() // Re-renders the map with all aggregated alerts
                }
            }
        }
    }


    /**
     * UPDATED: Function to reconstruct PlotDetail from flat lists, converting to LatLng,
     * and correctly mapping the user-defined plot name.
     */
    private fun reconstructPlotDetails(
        coordMaps: List<Map<String, Any>>,
        crops: List<String>,
        irrigations: List<String>,
        soils: List<String>,
        plotNames: List<String>
    ): List<PlotDetail> {
        val plotDetails = mutableListOf<PlotDetail>()
        var currentCoordinates = ArrayList<LatLng>()
        var attributeIndex = 0

        for (coordMaps in coordMaps) {
            val isSeparator = coordMaps["separator"] == true

            if (isSeparator) {
                if (currentCoordinates.size >= 3 && attributeIndex < crops.size) {
                    plotDetails.add(
                        PlotDetail(
                            name = plotNames[attributeIndex],
                            crop = crops[attributeIndex],
                            irrigationType = irrigations[attributeIndex],
                            soilType = soils[attributeIndex],
                            coordinates = currentCoordinates.toList()
                        )
                    )
                    attributeIndex++
                }
                currentCoordinates = ArrayList()
            } else {
                val lat = coordMaps["latitude"] as? Double
                val lng = coordMaps["longitude"] as? Double
                if (lat != null && lng != null) {
                    currentCoordinates.add(LatLng(lat, lng))
                }
            }
        }

        // Handle the last plot
        if (currentCoordinates.size >= 3 && attributeIndex < crops.size) {
            plotDetails.add(
                PlotDetail(
                    name = plotNames[attributeIndex],
                    crop = crops[attributeIndex],
                    irrigationType = irrigations[attributeIndex],
                    soilType = soils[attributeIndex],
                    coordinates = currentCoordinates.toList()
                )
            )
        }

        return plotDetails
    }


    private fun saveFarmLocation() {
        // ... (This function remains focused on updating base user lat/lng)
        val uid = auth.currentUser?.uid ?: return

        val updates = mutableMapOf<String, Any?>()
        val firstPlotAnchor = farmPlotDetailsList?.firstOrNull()?.coordinates?.firstOrNull()

        updates["lat"] = firstPlotAnchor?.latitude ?: 0.0
        updates["lng"] = firstPlotAnchor?.longitude ?: 0.0

        db.collection("users").document(uid).update(updates)
            .addOnSuccessListener {
                if (isAdded) {
                    Toast.makeText(context, "Primary anchor location updated (using Plot 1).", Toast.LENGTH_SHORT).show()
                    setupMap()
                }
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Log.e(TAG, "FIREBASE UPDATE FAILED: ${e.message}", e)
                    Toast.makeText(context, "Error saving primary location.", Toast.LENGTH_LONG).show()
                }
            }
    }

    /**
     * UPDATED: Saves the map snapshot to the cache.
     */
    private fun saveProfileSnapshot(map: GoogleMap) {
        if (!isAdded) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val editor = prefs.edit()

        // Use first plot anchor as the main profile location snapshot
        val firstPlotAnchor = farmPlotDetailsList?.firstOrNull()?.coordinates?.firstOrNull()
        val primaryLat = firstPlotAnchor?.latitude ?: 0.0
        val primaryLng = firstPlotAnchor?.longitude ?: 0.0

        editor.putString("farmer_name", farmerName)
        editor.putString("farmer_address", farmerAddress)
        editor.putString("farmer_lat", primaryLat.format(6))
        editor.putString("farmer_lng", primaryLng.format(6))
        editor.apply()

        val callback = GoogleMap.SnapshotReadyCallback { snapshotBitmap ->
            if (snapshotBitmap != null) {
                try {
                    val file = File(requireContext().cacheDir, MAP_SNAPSHOT_FILENAME)
                    val out = FileOutputStream(file)
                    snapshotBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    out.flush()
                    out.close()
                    Log.d(TAG, "Saved map snapshot to cache.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save map snapshot: ${e.message}", e)
                }
            }
        }
        map.snapshot(callback)
    }


    /**
     * Loads the disease detection history saved by DiseaseFragment.
     */
    private fun loadLocalHistory() {
        val searches = requireActivity().getSharedPreferences("DiseaseHistory", Context.MODE_PRIVATE)
        val historyMap = searches.all

        if (historyMap.isNotEmpty()) {
            val historyBuilder = StringBuilder()

            val sortedHistory = historyMap.toSortedMap(compareByDescending { it.toLongOrNull() ?: 0L })

            sortedHistory.forEach { (_, value) ->
                historyBuilder.append("• ").append(value.toString()).append("\n")
            }

            if (historyBuilder.isNotEmpty()) {
                historyBuilder.setLength(historyBuilder.length - 1)
            }

            tvLocalHistory.text = historyBuilder.toString()
        } else {
            tvLocalHistory.text = getString(R.string.message_no_disease_history)
        }
    }

    /**
     * UPDATED: Now checks if the map is loaded before proceeding with rendering.
     * If loaded, it hides the snapshot and renders. Otherwise, the snapshot remains.
     */
    private fun setupMap() {
        val map = googleMapInstance
        if (map == null) {
            Log.d(TAG, "GoogleMap not yet ready. Map will be set up when async task completes.")
            // Keep snapshot visible as map has not loaded successfully yet
            ivMapSnapshot.visibility = View.VISIBLE
            mapFragmentContainer.visibility = View.GONE
            return
        }

        // Map is ready, hide the snapshot and show the map container
        ivMapSnapshot.visibility = View.GONE
        mapFragmentContainer.visibility = View.VISIBLE

        map.clear()

        map.mapType = GoogleMap.MAP_TYPE_SATELLITE
        map.setTrafficEnabled(false)

        val plots = farmPlotDetailsList
        val plotsExist = plots != null && plots.isNotEmpty()

        val boundsBuilder = LatLngBounds.Builder()

        if (plotsExist) {
            plots!!.forEach { plotDetail ->
                plotDetail.coordinates.forEach { boundsBuilder.include(it) }
                // Draw both pin and emoji markers
                drawPlotOverlays(plotDetail, map)
            }

            val bounds = boundsBuilder.build()
            // Center the map on the calculated bounds with padding (100 pixels)
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))

            // Overlay Alerts (Draw alerts around the plots)
            farmAlerts.forEach { alert ->
                drawAlertMarker(alert, map)
            }

        } else {
            // Default fallback if no plots are defined
            val centerPoint = LatLng(20.5937, 78.9629)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(centerPoint, 5f))
        }

        saveProfileSnapshot(map)
    }

    /**
     * Renders a single plot polygon, places a location pin on the first point,
     * AND places the crop emoji marker at the plot's center.
     */
    private fun drawPlotOverlays(plotData: PlotDetail, map: GoogleMap) {

        val points = plotData.coordinates
        val plotName = plotData.name

        // --- 1. SOIL TYPE (Polygon Fill) & STROKE ---
        val soilColor = getColorForSoil(plotData.soilType)
        val fillColor = (0x70 shl 24) or (soilColor and 0x00FFFFFF) // 44% Alpha

        val strokeColorRes = when (plotData.irrigationType.trim().lowercase(Locale.ROOT)) {
            "drip" -> R.color.irrigation_blue_dark
            "canal" -> R.color.irrigation_cyan
            "flood" -> R.color.irrigation_green
            else -> R.color.white
        }
        val strokeColor = ContextCompat.getColor(requireContext(), strokeColorRes)

        // Draw the Polygon
        val polygon = PolygonOptions()
            .addAll(points)
            .strokeColor(strokeColor)
            .strokeWidth(8f)
            .fillColor(fillColor)

        map.addPolygon(polygon).tag = "$plotName - ${plotData.soilType}"

        // Calculate Center for the Emoji Marker
        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        val center = LatLng(
            (bounds.southwest.latitude + bounds.northeast.latitude) / 2,
            (bounds.southwest.longitude + bounds.northeast.longitude) / 2
        )

        // --- 2. CROP EMOJI MARKER (At Center) ---
        val cropEmoji = getCropEmoji(plotData.crop)
        val cropIcon = textToBitmap(cropEmoji, 48f, ContextCompat.getColor(requireContext(), R.color.white))

        map.addMarker(
            MarkerOptions()
                .position(center) // Center position
                .title(plotData.crop)
                .snippet("Plot: $plotName")
                .icon(BitmapDescriptorFactory.fromBitmap(cropIcon))
                .anchor(0.5f, 0.5f)
        )

        // --- 3. LOCATION SYMBOL MARKER (Pin on the first point) ---
        val firstPoint = points.first()

        map.addMarker(
            MarkerOptions()
                .position(firstPoint) // First coordinate position
                .title("Location Anchor: $plotName")
                .snippet("Coordinates: ${firstPoint.latitude.format(4)}, ${firstPoint.longitude.format(4)}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }

    private fun getColorForSoil(soilType: String): Int {
        val soilColorRes = when (soilType.trim().lowercase(Locale.ROOT)) {
            "black", "black soil" -> R.color.soil_black
            "red", "red soil" -> R.color.soil_red
            "alluvial", "alluvial soil" -> R.color.soil_yellow
            "sandy" -> R.color.soil_sandy
            "loamy" -> R.color.md_theme_primaryContainer
            else -> R.color.md_theme_secondary
        }
        return try {
            ContextCompat.getColor(requireContext(), soilColorRes)
        } catch (e: Exception) {
            Color.GRAY
        }
    }

    private fun getCropEmoji(crop: String): String {
        return when (crop.trim().lowercase(Locale.ROOT)) {
            "rice", "paddy" -> "🍚"
            "wheat" -> "🌾"
            "maize", "corn" -> "🌽"
            "sugarcane" -> "🌿"
            "cotton" -> "☁️"
            "soybean" -> "🥜"
            "banana" -> "🍌"
            "mango" -> "🥭"
            "potato" -> "🥔"
            "tomato" -> "🍅"
            "chilli", "pepper" -> "🌶️"
            else -> "🌱" // Default to seedling
        }
    }

    private fun textToBitmap(text: String, textSize: Float, textColor: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            color = textColor
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT_BOLD
        }
        val textBounds = Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)

        val padding = 10
        val width = (textBounds.width() + padding * 2)
        val height = (textBounds.height() + padding * 2)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawText(text, padding.toFloat(), (height - padding).toFloat(), paint)

        return bitmap
    }


    /**
     * Draws an alert marker with specific color and size based on the alert type AND severity.
     */
    private fun drawAlertMarker(alert: Alert, map: GoogleMap) {
        val type = alert.type.lowercase(Locale.ROOT)

        val severityColor = when (alert.severity.lowercase(Locale.ROOT)) {
            "high" -> BitmapDescriptorFactory.HUE_RED
            "medium" -> BitmapDescriptorFactory.HUE_ORANGE
            "low" -> BitmapDescriptorFactory.HUE_YELLOW
            else -> BitmapDescriptorFactory.HUE_MAGENTA
        }

        val icon = when {
            type.contains("regional") || type.contains("outbreak") -> BitmapDescriptorFactory.defaultMarker(severityColor)
            type.contains("disease") || type.contains("hotspot") || type.contains("pest") -> BitmapDescriptorFactory.defaultMarker(severityColor)
            type.contains("climate") || type.contains("weather") -> BitmapDescriptorFactory.defaultMarker(severityColor)
            type.contains("soil health") || type.contains("nutrient") -> BitmapDescriptorFactory.defaultMarker(severityColor)
            else -> BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA)
        }

        map.addMarker(
            MarkerOptions()
                .position(LatLng(alert.lat, alert.lng))
                .title(alert.type)
                .snippet(alert.message + " (Severity: ${alert.severity})")
                .icon(icon)
        )
    }



    private fun performLogout() {
        auth.signOut()

        googleSignInClient.signOut().addOnCompleteListener(requireActivity()) {
            Toast.makeText(context, R.string.message_logged_out, Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    private fun Double.format(digits: Int) = String.format(Locale.US, "%.${digits}f", this)

    private fun launchMapActivityForPolygon() {
        val intent = Intent(requireContext(), MapSelectActivity::class.java).apply {

            val plots = farmPlotDetailsList
            val plotNames = farmPlotNamesList

            if (plots != null && plots.isNotEmpty() && plotNames != null && plotNames.isNotEmpty()) {
                val cropList = plots.map { it.crop }
                val irrigationList = plots.map { it.irrigationType }
                val soilList = plots.map { it.soilType }

                // Reconstruct the flat coordinate list to pass back to MapSelectActivity
                val coordMaps = plots.flatMapIndexed { index, plot ->
                    val plotCoords = plot.coordinates.map { latLng ->
                        mapOf("latitude" to latLng.latitude as Double, "longitude" to latLng.longitude as Double)
                    }
                    if (index < plots.size - 1) {
                        // Pass separator data using the GeoPoint constant values
                        plotCoords + mapOf("separator" to true, "latitude" to PLOT_SEPARATOR.latitude, "longitude" to PLOT_SEPARATOR.longitude)
                    } else {
                        plotCoords
                    }
                }

                putExtra("plot_names", ArrayList(plotNames) as Serializable)
                putExtra("plot_crops", ArrayList(cropList) as Serializable)
                putExtra("plot_irrigations", ArrayList(irrigationList) as Serializable)
                putExtra("plot_soils", ArrayList(soilList) as Serializable)
                putExtra("plot_coordinates_flat", coordMaps as Serializable)
            }

            putExtra("userId", auth.currentUser?.uid)
            putExtra("mode", "POLYGON_SELECT_PLOTS")
        }
        mapActivityResultLauncher.launch(intent)
    }
}