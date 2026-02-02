package com.farmerassistant.app.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.farmerassistant.app.R
import com.farmerassistant.app.ui.maps.MapSelectActivity
import com.farmerassistant.app.ui.home.HomeActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.auth.FirebaseAuth
import java.io.Serializable
import com.farmerassistant.app.utils.LanguageHelper

class RegisterActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private val db by lazy { Firebase.firestore }


    private var plotNames: List<String>? = null
    private var plotCrops: List<String>? = null
    private var plotIrrigations: List<String>? = null
    private var plotSoils: List<String>? = null
    private var plotCoordinatesFlat: List<Map<String, Any>>? = null

    private var primaryLat: Double = 0.0
    private var primaryLng: Double = 0.0
    private lateinit var tvFarmLocation: TextView


    private val mapActivityResultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                handleMapActivityResult(data)
            }
        }


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        tvFarmLocation = findViewById(R.id.tvFarmLocation)


        findViewById<Button>(R.id.btnMapPicker).setOnClickListener {
            val intent = Intent(this, MapSelectActivity::class.java).apply {
                putExtra("mode", "POLYGON_SELECT_PLOTS")

                if (plotNames != null) putExtra("plot_names", plotNames as Serializable)
                if (plotCrops != null) putExtra("plot_crops", plotCrops as Serializable)
                if (plotIrrigations != null) putExtra("plot_irrigations", plotIrrigations as Serializable)
                if (plotSoils != null) putExtra("plot_soils", plotSoils as Serializable)
                if (plotCoordinatesFlat != null) putExtra("plot_coordinates_flat", plotCoordinatesFlat as Serializable)

                putExtra("anchor_lat", primaryLat)
                putExtra("anchor_lng", primaryLng)
            }
            mapActivityResultLauncher.launch(intent)
        }
        findViewById<Button>(R.id.btnRegister).setOnClickListener { registerUser() }
    }

    private fun handleMapActivityResult(data: Intent?) {
        // --- CRITICAL: RECEIVE ALL FLATTENED LISTS ---
        @Suppress("UNCHECKED_CAST")
        plotNames = data?.getSerializableExtra("plot_names") as? List<String>
        @Suppress("UNCHECKED_CAST")
        plotCrops = data?.getSerializableExtra("plot_crops") as? List<String>
        @Suppress("UNCHECKED_CAST")
        plotIrrigations = data?.getSerializableExtra("plot_irrigations") as? List<String>
        @Suppress("UNCHECKED_CAST")
        plotSoils = data?.getSerializableExtra("plot_soils") as? List<String>
        @Suppress("UNCHECKED_CAST")
        plotCoordinatesFlat = data?.getSerializableExtra("plot_coordinates_flat") as? List<Map<String, Any>>

        val anchorLat = data?.getDoubleExtra("anchor_lat", 0.0) ?: 0.0
        val anchorLng = data?.getDoubleExtra("anchor_lng", 0.0) ?: 0.0

        primaryLat = anchorLat
        primaryLng = anchorLng

        val numPlots = plotCrops?.size ?: 0

        if (numPlots > 0 && (primaryLat != 0.0 || primaryLng != 0.0)) {
            tvFarmLocation.text = getString(R.string.message_plots_set, numPlots) +
                    getString(R.string.message_primary_location,
                        String.format("%.4f", primaryLat),
                        String.format("%.4f", primaryLng))
        } else {
            // Clear all data if plots were attempted but failed validation
            plotNames = null; plotCrops = null; plotIrrigations = null; plotSoils = null; plotCoordinatesFlat = null
            primaryLat = 0.0
            primaryLng = 0.0
            tvFarmLocation.text = getString(R.string.message_location_not_selected)
            if (data != null) {
                Toast.makeText(this, "No valid plots added. Please draw and save at least one plot.", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun registerUser() {
        val email = findViewById<EditText>(R.id.etRegEmail).text.toString().trim()
        val pass = findViewById<EditText>(R.id.etRegPassword).text.toString().trim()
        val name = findViewById<EditText>(R.id.etName).text.toString().trim()
        val phone = findViewById<EditText>(R.id.etPhone).text.toString().trim()
        val state = findViewById<EditText>(R.id.etState).text.toString().trim()
        val district = findViewById<EditText>(R.id.etDistrict).text.toString().trim()
        val address = findViewById<EditText>(R.id.etAddress).text.toString().trim()


        val farmType = "Multi-Plot"
        val crops = plotCrops?.joinToString(", ") ?: "N/A"

        if (name.isEmpty() || primaryLat == 0.0) {
            Toast.makeText(this, R.string.error_missing_required_fields, Toast.LENGTH_LONG).show()
            return
        }

        val user = auth.currentUser

        if (user != null) {
            saveUserProfile(user.uid, name, phone, state, district, address)
            return
        }

        if (email.isEmpty() || pass.length < 6) {
            Toast.makeText(this, R.string.error_login_credentials_invalid, Toast.LENGTH_LONG).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, pass)
            .addOnSuccessListener { result ->
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    saveUserProfile(firebaseUser.uid, name, phone, state, district, address)
                } else {
                    Toast.makeText(this, "Error: Authentication failed to acquire UID.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun saveUserProfile(uid: String, name: String, phone: String, state: String, district: String, address: String) {

        val dataToSave = mutableMapOf<String, Any?>()
        dataToSave["name"] = name
        dataToSave["phone"] = phone
        dataToSave["state"] = state
        dataToSave["district"] = district
        dataToSave["address"] = address
        dataToSave["role"] = "farmer"
        dataToSave["createdAt"] = FieldValue.serverTimestamp()

        // Anchor points
        dataToSave["lat"] = primaryLat
        dataToSave["lng"] = primaryLng

        val numPlots = plotCrops?.size ?: 0


        val farmDataMap = mutableMapOf<String, Any?>()

        if (numPlots > 0 && plotNames != null) {

            farmDataMap["plots_names"] = plotNames
            farmDataMap["plots_crops"] = plotCrops
            farmDataMap["plots_irrigations"] = plotIrrigations
            farmDataMap["plots_soil"] = plotSoils
            farmDataMap["plots_coordinates_flat"] = plotCoordinatesFlat

            // Save anchor points inside farm_data
            farmDataMap["anchor_lat"] = primaryLat
            farmDataMap["anchor_lng"] = primaryLng

            dataToSave["farm_data"] = farmDataMap

        } else {
            dataToSave["farm_data"] = null
        }


        db.collection("users").document(uid).set(dataToSave as Map<String, Any>)
            .addOnSuccessListener {
                Toast.makeText(this, R.string.message_profile_saved, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, HomeActivity::class.java)); finish()
            }
            .addOnFailureListener { e ->
                Log.e("RegisterActivity", "Firestore Profile Write Failed: ${e.message}", e)
                Toast.makeText(this, "Error saving profile: Check Firebase Rules/Connection: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}