package com.farmerassistant.app.ui.maps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.farmerassistant.app.R
import com.farmerassistant.app.utils.LanguageHelper
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.PolyUtil
import java.io.Serializable
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicLong
import org.osmdroid.util.GeoPoint
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.LatLngBounds

// DATA STRUCTURES
data class PlotAttributes(
    var plotName: String, // <--- NEW FIELD
    var crop: String,
    var irrigationType: String,
    var soilType: String
) : Serializable

// NEW MASTER DATA STRUCTURE
data class FarmPlot(
    val id: Long, // Unique ID for keying/lookup
    var coords: MutableList<GeoPoint>,
    var attributes: PlotAttributes,
    var isNew: Boolean = false // Flag for newly created plots before first save/edit
) : Serializable

class MapSelectActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener, GoogleMap.OnMapLongClickListener, GoogleMap.OnMarkerClickListener, GoogleMap.OnMarkerDragListener {

    private val TAG = "MapSelectActivity"


    private var db: FirebaseFirestore? = null


    private var currentUserId: String? = "GUEST_MAP_USER_ID"

    private val PLOTS_DOCUMENT_PATH = "farm_data"


    // UI components
    private lateinit var googleMapView: MapView
    private var googleMapInstance: GoogleMap? = null
    private lateinit var btnConfirm: Button
    private lateinit var btnAddNextPlot: Button
    private lateinit var btnDeletePlot: Button
    private lateinit var mapTypeToggleGroup: MaterialButtonToggleGroup

    // UI Components for Plot Details
    private lateinit var etPlotName: EditText // <--- NEW BINDING
    private lateinit var etCropType: EditText
    private lateinit var etIrrigationType: EditText
    private lateinit var etSoilType: EditText

    // Core constants
    private val PLOT_SEPARATOR = GeoPoint(999.0, 999.0) // Kept for legacy compatibility but not used in save map data
    private val plotIdCounter = AtomicLong(System.currentTimeMillis())


    private var allFarmPlots: MutableList<FarmPlot> = mutableListOf()


    private var activePlot: FarmPlot? = null


    private val vertexMarkers: MutableList<Marker> = mutableListOf()
    private val polygonOverlays: MutableList<Polygon> = mutableListOf()
    private val polylineOverlays: MutableList<Polyline> = mutableListOf()

    private var mode: String = "POLYGON_SELECT_PLOTS"


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_select)

        // BINDINGS
        googleMapView = findViewById(R.id.mapView)
        btnConfirm = findViewById(R.id.confirmBtn)
        btnAddNextPlot = findViewById(R.id.btnAddNextPlot)
        mapTypeToggleGroup = findViewById(R.id.mapTypeToggleGroup)
        btnDeletePlot = findViewById(R.id.btnDeletePlot)

        // PLOT ATTRIBUTE BINDINGS
        etPlotName = findViewById(R.id.etPlotName) // <--- NEW BINDING
        etCropType = findViewById(R.id.etCropType)
        etIrrigationType = findViewById(R.id.etIrrigationType)
        etSoilType = findViewById(R.id.etSoilType)


        db = FirebaseFirestore.getInstance()
        loadIntentData()

        googleMapView.onCreate(savedInstanceState)
        googleMapView.getMapAsync(this)

        setupConfirmButton()
        setupMapTypeToggle()

        // ACTION: "Save Changes and Start New Plot"
        btnAddNextPlot.setOnClickListener {
            // This button saves the current plot and syncs immediately to Firebase
            saveActivePlot()
            prepareForNewPlot()
        }

        // ACTION: "Delete Plot"
        btnDeletePlot.setOnClickListener {
            deleteActivePlot()
        }

        // Initialize UI state
        if (allFarmPlots.isEmpty()) {
            prepareForNewPlot()
        } else {
            // Set the first loaded plot as the active one for potential editing
            activePlot = allFarmPlots.first()
            loadPlotDetailsToUI(activePlot!!)
        }
        updateUIForActivePlot()
    }

    private fun setupMapTypeToggle() {
        mapTypeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                googleMapInstance?.mapType = when (checkedId) {
                    R.id.btnMapTypeSatellite -> GoogleMap.MAP_TYPE_SATELLITE
                    R.id.btnMapTypeNormal -> GoogleMap.MAP_TYPE_NORMAL
                    else -> GoogleMap.MAP_TYPE_SATELLITE
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        googleMapInstance = googleMap

        googleMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
        mapTypeToggleGroup.check(R.id.btnMapTypeSatellite)

        googleMap.setOnMapLongClickListener(this)
        googleMap.setOnMarkerClickListener(this)
        googleMap.setOnMapClickListener(this)
        googleMap.setOnMarkerDragListener(this)

        val initialCenter = allFarmPlots.firstOrNull()?.coords?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(20.5937, 78.9629)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialCenter, if (allFarmPlots.isNotEmpty()) 14f else 5f))

        drawAllPlots()
    }

    override fun onMarkerClick(marker: Marker): Boolean {
        val tagData = marker.tag as? Pair<*, *>
        val clickedGeoPoint = tagData?.first as? GeoPoint
        val plotId = tagData?.second as? Long

        if (clickedGeoPoint != null && plotId != null) {
            val plotToEdit = allFarmPlots.find { it.id == plotId } ?: activePlot.takeIf { it?.id == plotId }

            if (plotToEdit != null) {
                removeSingleVertex(plotToEdit, clickedGeoPoint)
                if (plotToEdit.isNew == false && plotToEdit != activePlot) {
                    activePlot = plotToEdit
                    loadPlotDetailsToUI(activePlot!!)
                    updateUIForActivePlot()
                }
                // SYNC: We call Firebase sync after removing a vertex
                syncPlotsToFirebase(allFarmPlots.filter { !it.isNew })
            }
        }
        return true // Consume the click
    }

    override fun onMapLongClick(latLng: LatLng) {
        if (mode == "POLYGON_SELECT_PLOTS" && activePlot != null) {
            addPolygonVertex(latLng)
        } else {
            Toast.makeText(this, "Map is in view-only mode or no plot is active.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMapClick(latLng: LatLng) {
        val clickedLatLng = latLng

        val clickedPlot = allFarmPlots.find { plot ->
            val polygonPoints = plot.coords.map { LatLng(it.latitude, it.longitude) }
            if (polygonPoints.size >= 3) {
                PolyUtil.containsLocation(clickedLatLng, polygonPoints, false)
            } else false
        }

        if (clickedPlot != null) {
            if (activePlot?.id != clickedPlot.id) {
                if (activePlot?.coords?.size ?: 0 >= 3 && activePlot?.isNew == true) {
                    saveActivePlot()
                }

                activePlot = clickedPlot
                activePlot?.isNew = false
                loadPlotDetailsToUI(activePlot!!)
                updateUIForActivePlot()
                drawAllPlots()
            }
        }
    }

    override fun onMarkerDragStart(marker: Marker) {}

    override fun onMarkerDrag(marker: Marker) {}

    override fun onMarkerDragEnd(marker: Marker) {
        val tagData = marker.tag as? Pair<*, *>
        val draggedGeoPoint = tagData?.first as? GeoPoint
        val plotId = tagData?.second as? Long
        val newLatLng = marker.position
        val newGeoPoint = GeoPoint(newLatLng.latitude, newLatLng.longitude)

        if (draggedGeoPoint != null && plotId != null) {
            val plotToUpdate = allFarmPlots.find { it.id == plotId } ?: activePlot.takeIf { it?.id == plotId }

            if (plotToUpdate != null) {
                val indexToUpdate = plotToUpdate.coords.indexOf(draggedGeoPoint)

                if (indexToUpdate != -1) {

                    plotToUpdate.coords[indexToUpdate] = newGeoPoint
                    marker.tag = Pair(newGeoPoint, plotId)
                    Toast.makeText(this, "Point moved successfully. Plot ${plotToUpdate.id} selected for edit.", Toast.LENGTH_SHORT).show()

                    if (plotToUpdate != activePlot) {
                        activePlot = plotToUpdate
                        loadPlotDetailsToUI(activePlot!!)
                        updateUIForActivePlot()
                    }
                    drawAllPlots()
                    // SYNC: Update Firebase immediately after dragging a point
                    syncPlotsToFirebase(allFarmPlots.filter { !it.isNew })
                }
            }
        }
    }


    private fun loadIntentData() {
        mode = intent.getStringExtra("mode") ?: "POLYGON_SELECT_PLOTS"

        // Try to get authenticated ID, falling back to GUEST ID if null.
        currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: intent.getStringExtra("userId") ?: "GUEST_MAP_USER_ID"

        if (currentUserId == "GUEST_MAP_USER_ID") {
            Log.w(TAG, "WARNING: Using GUEST_MAP_USER_ID. Sync is working, but ensure proper authentication is implemented.")
        } else {
            Log.i(TAG, "Authenticated User ID loaded: $currentUserId")
        }

        // --- NEW: Load plots_names list from intent ---
        @Suppress("UNCHECKED_CAST")
        val plotNames = intent.getSerializableExtra("plot_names") as? ArrayList<String>
        // ---------------------------------------------

        @Suppress("UNCHECKED_CAST")
        val crops = intent.getSerializableExtra("plot_crops") as? ArrayList<String>
        @Suppress("UNCHECKED_CAST")
        val irrigations = intent.getSerializableExtra("plot_irrigations") as? ArrayList<String>
        @Suppress("UNCHECKED_CAST")
        val soils = intent.getSerializableExtra("plot_soils") as? ArrayList<String>
        @Suppress("UNCHECKED_CAST")
        val coordMaps = intent.getSerializableExtra("plot_coordinates_flat") as? ArrayList<Map<String, Any>>

        if (crops != null && coordMaps != null) {
            reconstructPlotState(coordMaps, plotNames, crops, irrigations, soils) // <--- UPDATED CALL
        }
    }

    private fun reconstructPlotState(
        coordMaps: List<Map<String, Any>>,
        plotNames: List<String>?, // <--- NEW ARGUMENT
        crops: List<String>,
        irrigations: List<String>?,
        soils: List<String>?
    ) {
        allFarmPlots.clear()

        var currentPlotCoords = mutableListOf<GeoPoint>()
        var attributeIndex = 0

        for (coordMap in coordMaps) {
            val isSeparator = coordMap["separator"] == true
            if (isSeparator) {
                if (currentPlotCoords.isNotEmpty() && attributeIndex < crops.size) {
                    val attributes = PlotAttributes(
                        plotNames?.getOrElse(attributeIndex) { "Plot ${attributeIndex + 1}" } ?: "Plot ${attributeIndex + 1}", // <--- NEW
                        crops[attributeIndex],
                        irrigations?.getOrElse(attributeIndex) { "Unknown" } ?: "Unknown",
                        soils?.getOrElse(attributeIndex) { "Unknown" } ?: "Unknown"
                    )

                    allFarmPlots.add(FarmPlot(plotIdCounter.incrementAndGet(), ArrayList(currentPlotCoords), attributes, isNew = false))
                    attributeIndex++
                }
                currentPlotCoords = mutableListOf()
            } else {
                val lat = coordMap["latitude"] as? Double
                val lng = coordMap["longitude"] as? Double
                if (lat != null && lng != null) {
                    currentPlotCoords.add(GeoPoint(lat, lng))
                }
            }
        }

        if (currentPlotCoords.isNotEmpty() && attributeIndex < crops.size) {
            val attributes = PlotAttributes(
                plotNames?.getOrElse(attributeIndex) { "Plot ${attributeIndex + 1}" } ?: "Plot ${attributeIndex + 1}", // <--- NEW
                crops[attributeIndex],
                irrigations?.getOrElse(attributeIndex) { "Unknown" } ?: "Unknown",
                soils?.getOrElse(attributeIndex) { "Unknown" } ?: "Unknown"
            )
            allFarmPlots.add(FarmPlot(plotIdCounter.incrementAndGet(), ArrayList(currentPlotCoords), attributes, isNew = false))
        }
    }


    private fun prepareForNewPlot() {
        // Start a new temporary plot
        activePlot = FarmPlot(
            id = plotIdCounter.incrementAndGet(),
            coords = mutableListOf(),
            attributes = PlotAttributes("", "", "", ""), // <--- UPDATED
            isNew = true
        )
        clearPlotDetailsUI()
        updateUIForActivePlot()
        drawAllPlots()
        Toast.makeText(this, "New plot started. Add points now.", Toast.LENGTH_LONG).show()
    }

    private fun saveActivePlot() {
        val plot = activePlot ?: run {
            Toast.makeText(this, "No plot active to save.", Toast.LENGTH_SHORT).show(); return
        }

        if (plot.coords.size < 3) {
            Toast.makeText(this, getString(R.string.error_plot_min_points), Toast.LENGTH_SHORT).show(); return
        }

        // --- NEW VALIDATION ---
        val plotName = etPlotName.text.toString().trim().ifEmpty {
            Toast.makeText(this, "Please enter Plot Name.", Toast.LENGTH_LONG).show(); return
        }
        // ----------------------

        val crop = etCropType.text.toString().trim().ifEmpty {
            Toast.makeText(this, "Please enter Crop Type.", Toast.LENGTH_LONG).show(); return
        }
        val irrigation = etIrrigationType.text.toString().trim().ifEmpty {
            Toast.makeText(this, "Please enter Irrigation Type.", Toast.LENGTH_LONG).show(); return
        }
        val soil = etSoilType.text.toString().trim().ifEmpty {
            Toast.makeText(this, "Please enter Soil Type.", Toast.LENGTH_LONG).show(); return
        }

        plot.attributes.plotName = plotName // <--- NEW SAVE
        plot.attributes.crop = crop
        plot.attributes.irrigationType = irrigation
        plot.attributes.soilType = soil

        // 3. Add or Update the plot in the master list
        if (plot.isNew) {
            allFarmPlots.add(plot)
            plot.isNew = false
        } else {
            val index = allFarmPlots.indexOfFirst { it.id == plot.id }
            if (index != -1) {
                allFarmPlots[index] = plot
            }
        }

        // 4. SYNC TO FIREBASE (Immediate sync for plot save)
        syncPlotsToFirebase(allFarmPlots.filter { !it.isNew })

        Toast.makeText(this, getString(R.string.message_plot_saved_or_updated, allFarmPlots.filter { !it.isNew }.size), Toast.LENGTH_LONG).show()
        drawAllPlots()
    }

    private fun deleteActivePlot() {
        val plot = activePlot ?: return

        // 1. Remove the plot from the list
        allFarmPlots.remove(plot)
        Toast.makeText(this, getString(R.string.message_plot_deleted), Toast.LENGTH_LONG).show()

        // 2. SYNC TO FIREBASE (Immediate sync for deletion)
        syncPlotsToFirebase(allFarmPlots.filter { !it.isNew })

        // 3. Prepare for a new plot after deletion/sync
        prepareForNewPlot()
    }

    private fun removeSingleVertex(plot: FarmPlot, p: GeoPoint) {
        plot.coords.remove(p)
        drawAllPlots()
        Toast.makeText(this, "Point removed from Plot ${plot.id}.", Toast.LENGTH_SHORT).show()
    }

    private fun setupConfirmButton() {
        btnConfirm.setOnClickListener {
            val currentPlot = activePlot

            if (currentPlot != null && (currentPlot.coords.size ?: 0) >= 3 && currentPlot.isNew) {
                saveActivePlot()
            } else if (currentPlot != null && (currentPlot.coords.size ?: 0) > 0 && currentPlot.isNew) {
                // If it's a new plot with points but not enough to save, discard it upon confirmation
                allFarmPlots.remove(currentPlot)
            } else if (currentPlot != null && currentPlot.isNew == false) {
                // Save changes to the active plot before confirming final data
                saveActivePlot()
            }

            if (allFarmPlots.filter { !it.isNew }.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_min_one_plot), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            returnPolygonData()
        }
    }

    // --- UI Helpers ---

    private fun updateUIForActivePlot() {
        val savedPlotsCount = allFarmPlots.count { !it.isNew }
        if (activePlot != null) {
            btnDeletePlot.visibility = View.VISIBLE
            btnConfirm.text = getString(R.string.btn_confirm_all_boundaries, savedPlotsCount)
            btnAddNextPlot.text = if (activePlot!!.isNew) {
                getString(R.string.btn_save_and_add_new_plot)
            } else {
                getString(R.string.btn_save_changes_and_add_new)
            }
        } else {
            btnDeletePlot.visibility = View.GONE
            btnConfirm.text = getString(R.string.btn_confirm_all_boundaries, savedPlotsCount)
            btnAddNextPlot.text = getString(R.string.btn_add_next_plot)
        }
    }

    private fun loadPlotDetailsToUI(plot: FarmPlot) {
        etPlotName.setText(plot.attributes.plotName) // <--- NEW
        etCropType.setText(plot.attributes.crop)
        etIrrigationType.setText(plot.attributes.irrigationType)
        etSoilType.setText(plot.attributes.soilType)
    }

    private fun clearPlotDetailsUI() {
        etPlotName.setText("") // <--- NEW
        etCropType.setText("")
        etIrrigationType.setText("")
        etSoilType.setText("")
    }

    private fun addPolygonVertex(latLng: LatLng) {
        val newPoint = GeoPoint(latLng.latitude, latLng.longitude)

        val firstPoint = allFarmPlots.firstOrNull()?.coords?.firstOrNull()

        activePlot?.coords?.add(newPoint)
        drawAllPlots()
        Toast.makeText(this, "Point added to current plot.", Toast.LENGTH_SHORT).show()
    }

    private fun drawAllPlots() {
        val map = googleMapInstance ?: return
        map.clear()
        vertexMarkers.clear()
        polygonOverlays.clear()
        polylineOverlays.clear()

        val plotsToDraw = allFarmPlots.filter { !it.isNew }.toMutableList()
        activePlot?.let {
            plotsToDraw.add(it)
        }

        plotsToDraw.forEach { plot ->
            val isCurrent = plot.id == activePlot?.id
            drawSinglePlot(plot, isCurrent)
        }

        activePlot?.let { drawMarkers(it) }

        updateUIForActivePlot()
    }

    private fun drawSinglePlot(plot: FarmPlot, isCurrent: Boolean) {
        val map = googleMapInstance ?: return
        val points = plot.coords.map { LatLng(it.latitude, it.longitude) }

        val colorResId = if (isCurrent) R.color.md_theme_primary else R.color.md_theme_secondary
        val color = ContextCompat.getColor(this, colorResId)
        val alpha = if (isCurrent) 0x90 else 0x50

        if (points.size >= 3) {
            val polygon = PolygonOptions()
                .addAll(points)
                .strokeColor(color)
                .strokeWidth(3f)
                .fillColor((alpha shl 24) or (color and 0x00FFFFFF))

            map.addPolygon(polygon).tag = plot.id
        }

        if (isCurrent && points.size < 3 && points.size >= 2) {
            val polyline = PolylineOptions()
                .addAll(points)
                .color(color)
                .width(5f)
            map.addPolyline(polyline)
        }
    }

    private fun drawMarkers(plotToMark: FarmPlot) {
        val map = googleMapInstance ?: return
        val points = plotToMark.coords
        val isCurrent = plotToMark.id == activePlot?.id

        points.forEachIndexed { index, geoPoint ->
            val latLng = LatLng(geoPoint.latitude, geoPoint.longitude)

            val markerColor = if (isCurrent) BitmapDescriptorFactory.HUE_AZURE else BitmapDescriptorFactory.HUE_YELLOW

            val vertexMarker = map.addMarker(MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
                .title("Plot ${plotToMark.id}, Vertex ${index + 1}")
                .snippet("Tap to remove point.")
                .draggable(true))

            vertexMarker?.tag = Pair(geoPoint, plotToMark.id)
            vertexMarkers.add(vertexMarker!!)
        }
    }


    // --- FIREBASE SYNCHRONIZATION LOGIC (Real-time updates) ---

    private fun syncPlotsToFirebase(plots: List<FarmPlot>) {
        val currentDb = db ?: run {
            Log.e(TAG, "Firestore sync failed: Database instance is null.")
            Toast.makeText(this, "Cannot sync: Database not initialized.", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = currentUserId ?: run {
            Log.e(TAG, "Firestore sync failed: User ID is null/missing.")
            Toast.makeText(this, "Cannot sync: User ID missing.", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Prepare data structures to be saved
        val plotNames = plots.map { it.attributes.plotName } // <--- NEW LIST
        val crops = plots.map { it.attributes.crop }
        val irrigations = plots.map { it.attributes.irrigationType }
        val soils = plots.map { it.attributes.soilType }

        val coordMaps = mutableListOf<Map<String, Any>>()
        var anchorLat = 0.0
        var anchorLng = 0.0

        plots.forEachIndexed { index, plot ->
            if (index == 0 && plot.coords.isNotEmpty()) {
                anchorLat = plot.coords.first().latitude
                anchorLng = plot.coords.first().longitude
            }

            plot.coords.mapTo(coordMaps) { geoPoint ->
                mapOf("latitude" to geoPoint.latitude as Double, "longitude" to geoPoint.longitude as Double)
            }
            // 🔥 CRITICAL FIX: Save a clean separator without coordinates
            coordMaps.add(mapOf("separator" to true))
        }

        if (coordMaps.lastOrNull()?.get("separator") == true) {
            coordMaps.removeAt(coordMaps.size - 1)
        }

        val farmData = hashMapOf(
            "plots_names" to plotNames, // <--- NEW FIELD
            "plots_crops" to crops,
            "plots_irrigations" to irrigations,
            "plots_soil" to soils,
            "plots_coordinates_flat" to coordMaps,
            "anchor_lat" to anchorLat,
            "anchor_lng" to anchorLng
        )

        Log.i(TAG, "--- FIREBASE SYNC: START ---")
        Log.i(TAG, "Sending ${plots.size} finalized plots to users/$userId/$PLOTS_DOCUMENT_PATH")

        // 2. Perform the save/update operation
        val userRef = currentDb.collection("users").document(userId)

        // Use a map to update specific top-level fields
        val updates = hashMapOf<String, Any>(
            PLOTS_DOCUMENT_PATH to farmData, // Update farm_data map
            "lat" to anchorLat,              // Update top-level anchor lat
            "lng" to anchorLng               // Update top-level anchor lng
        )

        userRef.update(updates)
            .addOnSuccessListener {
                Log.d(TAG, "Firestore Sync Success: ${plots.size} plots synced.")
                btnConfirm.text = getString(R.string.btn_confirm_all_boundaries, plots.size)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore Sync Failed: ${e.message}", e)
                Toast.makeText(this, "Failed to sync plot changes: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }


    // --- RETURN DATA LOGIC (for original calling activity result) ---

    private fun returnPolygonData() {
        val anchorPoint = allFarmPlots.firstOrNull()?.coords?.firstOrNull()

        val plotList = allFarmPlots.filter { !it.isNew }

        val plotNames = plotList.map { it.attributes.plotName } // <--- NEW LIST
        val cropList = plotList.map { it.attributes.crop }
        val irrigationList = plotList.map { it.attributes.irrigationType }
        val soilList = plotList.map { it.attributes.soilType }

        val coordMaps = mutableListOf<Map<String, Any>>()
        plotList.forEach { plot ->
            plot.coords.mapTo(coordMaps) { geoPoint ->
                mapOf("latitude" to geoPoint.latitude as Double, "longitude" to geoPoint.longitude as Double)
            }
            // 🔥 CRITICAL FIX: Save a clean separator without coordinates
            coordMaps.add(mapOf("separator" to true))
        }
        if (coordMaps.lastOrNull()?.get("separator") == true) {
            coordMaps.removeAt(coordMaps.size - 1)
        }

        Log.i(TAG, "--- RETURNING FINAL DATA TO CALLING ACTIVITY ---")
        Log.i(TAG, "Total Finalized Plots: ${plotList.size}")
        Log.i(TAG, "--------------------------------------------------")

        val out = Intent().apply {
            putExtra("plot_names", plotNames as Serializable) // <--- NEW RETURN VALUE
            putExtra("plot_crops", cropList as Serializable)
            putExtra("plot_irrigations", irrigationList as Serializable)
            putExtra("plot_soils", soilList as Serializable)
            putExtra("plot_coordinates_flat", coordMaps as Serializable)
            putExtra("anchor_lat", anchorPoint?.latitude ?: 0.0)
            putExtra("anchor_lng", anchorPoint?.longitude ?: 0.0)
        }
        setResult(Activity.RESULT_OK, out)
        finish()
    }

    // Lifecycle overrides: These are required by MapView
    override fun onResume() {
        super.onResume()
        googleMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        googleMapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        googleMapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        googleMapView.onLowMemory()
    }
}