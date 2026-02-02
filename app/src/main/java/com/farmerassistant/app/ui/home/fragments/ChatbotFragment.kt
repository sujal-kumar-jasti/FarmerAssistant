package com.farmerassistant.app.ui.home.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.farmerassistant.app.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import androidx.lifecycle.lifecycleScope
import com.farmerassistant.app.BuildConfig
import com.farmerassistant.app.utils.LanguageHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.tasks.await
import android.location.Geocoder
import java.util.Locale
import com.farmerassistant.app.workers.SoilAnalysisWorker
import com.farmerassistant.app.workers.ClimateWorker
import com.farmerassistant.app.workers.YieldPredictionWorker
import com.google.android.material.button.MaterialButton

// Data class to represent a single message (Content part of the Gemini API)
data class Content(val role: String, val text: String)

// Data structure to pass summary worker data to the system prompt
data class PlotSummary(
    val soilStatus: String,
    val soilNPK: String,
    val climateTemp: String,
    val climatePrecip: String,
    val yieldAlert: String
)

// Assuming FarmField is defined and imported successfully elsewhere.
// data class FarmField(...)

class ChatbotFragment : Fragment() {
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var chat: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvStatus: TextView

    // UI elements for plot selector
    private lateinit var tilFarmSelector: TextInputLayout
    private lateinit var actFarmSelector: AutoCompleteTextView

    private lateinit var btnGoToCommunity: MaterialButton


    private val client = OkHttpClient()
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // Gemini setup
    private val geminiApiKey = BuildConfig.GEMINI_API_KEY
    private val modelUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$geminiApiKey"

    // Conversation History List (for context)
    private val conversationHistory = mutableListOf<Content>()

    // --- CACHE KEYS ---
    private val BASE_CHAT_HISTORY_KEY = "chatbot_conversation_history_"
    private val BASE_CHAT_TEXT_KEY = "chatbot_display_text_"
    // --- END CACHE KEYS ---

    // Dynamic System Instruction - Holds the persona and context
    private var systemInstructionPrompt = ""
    private var isProfileLoaded = false // CRITICAL FLAG

    // Plot Management Variables
    private var farmList: List<FarmField> = emptyList()
    private var selectedFarm: FarmField? = null

    // Constants for roles
    private val ROLE_USER = "user"
    private val ROLE_MODEL = "model"


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_chat, container, false)
        input = v.findViewById(R.id.etInput)
        send = v.findViewById(R.id.btnSend)
        chat = v.findViewById(R.id.tvChat)
        scrollView = v.findViewById(R.id.chatScroll)
        tvStatus = v.findViewById(R.id.tvChatStatus)
        btnGoToCommunity = v.findViewById(R.id.btnGoToCommunity)

        // NEW BINDINGS
        tilFarmSelector = v.findViewById(R.id.tilFarmSelector)
        actFarmSelector = v.findViewById(R.id.actFarmSelector)

        // 1. Load profile and farm list (history load happens AFTER initial plot selection)
        loadProfileAndSetupChat()

        send.setOnClickListener {
            val query = input.text.toString().trim()

            if (query.isEmpty() || geminiApiKey.isEmpty() || geminiApiKey == "MISSING_API_KEY") {
                Toast.makeText(context, "Enter query and check API key setup.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isProfileLoaded || selectedFarm == null) {
                Toast.makeText(context, "Please wait, loading farm context...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            send.isEnabled = false
            input.setText("")

            sendMessage(query)
        }
        btnGoToCommunity.setOnClickListener {
            if (isAdded && parentFragmentManager != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, CommunityFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }

        // Setup listener for the plot selector field (opens the dialog)
        actFarmSelector.setOnClickListener {
            showPlotSelectionDialog()
        }

        // Set initial state for the selector text
        actFarmSelector.setText(selectedFarm?.name ?: getString(R.string.hint_select_farm), false)

        return v
    }

    override fun onPause() {
        super.onPause()
        // Save chat history for the currently selected plot when the user navigates away
        selectedFarm?.let { saveChatHistory(it.id) }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        processInitialPrompt()
    }


    // --- CACHING FUNCTIONS (UNCHANGED) ---

    private fun loadChatHistory(plotId: String) {
        if (!isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val historyKey = BASE_CHAT_HISTORY_KEY + plotId
        val textKey = BASE_CHAT_TEXT_KEY + plotId

        val cachedHistoryJson = prefs.getString(historyKey, null)
        val cachedText = prefs.getString(textKey, null)

        conversationHistory.clear()
        chat.text = ""

        if (cachedHistoryJson != null && cachedText != null) {
            try {
                chat.text = cachedText

                val historyArray = JSONArray(cachedHistoryJson)
                for (i in 0 until historyArray.length()) {
                    val jsonContent = historyArray.getJSONObject(i)
                    conversationHistory.add(
                        Content(
                            role = jsonContent.getString("role"),
                            text = jsonContent.getString("text")
                        )
                    )
                }
                Log.d("ChatbotFragment", "Loaded ${conversationHistory.size} items for plot $plotId from cache.")
                scrollToBottom()
            } catch (e: Exception) {
                Log.e("ChatbotFragment", "Error loading chat history for $plotId: ${e.message}. Clearing cache.")
                prefs.edit().remove(historyKey).remove(textKey).apply()
            }
        } else {
            Log.d("ChatbotFragment", "No chat history found for plot $plotId.")
        }
    }

    private fun saveChatHistory(plotId: String) {
        if (!isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val historyKey = BASE_CHAT_HISTORY_KEY + plotId
        val textKey = BASE_CHAT_TEXT_KEY + plotId

        if (conversationHistory.isEmpty()) {
            prefs.edit().remove(historyKey).remove(textKey).apply()
            Log.d("ChatbotFragment", "Chat history for $plotId is empty, cache cleared.")
            return
        }

        val historyArray = JSONArray()
        conversationHistory.forEach { content ->
            historyArray.put(
                JSONObject().apply {
                    put("role", content.role)
                    put("text", content.text)
                }
            )
        }

        prefs.edit()
            .putString(historyKey, historyArray.toString())
            .putString(textKey, chat.text.toString())
            .apply()
        Log.d("ChatbotFragment", "Saved ${conversationHistory.size} items for plot $plotId to cache.")
    }
    // --- END CACHING FUNCTIONS ---


    /**
     * Geocodes the selected plot's coordinates (lat/lng) to get the most granular location string.
     */
    private suspend fun geocodePlotLocation(farm: FarmField): Pair<String, String> {
        if (!isAdded) return Pair("a general region", "N/A")

        val geocoder = Geocoder(requireContext(), Locale.getDefault())

        return withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(farm.lat, farm.lng, 3)

                if (!addresses.isNullOrEmpty()) {
                    val bestAddress = addresses.first()
                    val subAdmin = bestAddress.subAdminArea
                    val locality = bestAddress.locality
                    val subLocality = bestAddress.subLocality
                    val state = bestAddress.adminArea ?: "N/A"

                    val parts = mutableListOf<String>()
                    if (!subLocality.isNullOrEmpty()) parts.add(subLocality)
                    if (!locality.isNullOrEmpty() && locality != subLocality) parts.add(locality)
                    if (!subAdmin.isNullOrEmpty()) parts.add(subAdmin)

                    val granularLocation = parts.distinct().joinToString(", ")

                    val districtResult = if (granularLocation.isNotEmpty()) {
                        granularLocation
                    } else {
                        bestAddress.adminArea ?: "a general region"
                    }

                    return@withContext Pair(districtResult, state)
                }
            } catch (e: Exception) {
                Log.e("ChatbotFragment", "Geocoding plot failed: ${e.message}")
            }
            return@withContext Pair("a general region", "N/A")
        }
    }

    // --- START PHASE 14 IMPLEMENTATION: FETCHING WORKER DATA ---

    /**
     * Fetches the latest Soil, Climate, and Yield data for the specific plot.
     */
    private suspend fun fetchLatestFarmData(uid: String, plotId: String): PlotSummary {
        val docId = "${uid}_$plotId"
        var summary = PlotSummary(
            soilStatus = "Unknown",
            soilNPK = "N/P/K: -- / -- / --",
            climateTemp = "--°C",
            climatePrecip = "-- mm",
            yieldAlert = "No recent forecast."
        )

        try {
            val soilDoc = db.collection(SoilAnalysisWorker.SOIL_COLLECTION).document(docId).get().await()
            val climateDoc = db.collection(ClimateWorker.CLIMATE_COLLECTION).document(docId).get().await()
            val yieldDoc = db.collection(YieldPredictionWorker.YIELD_COLLECTION).document(docId).get().await()

            // 1. SOIL DATA
            if (soilDoc.exists()) {
                val n = soilDoc.getDouble("nitrogen")?.toInt() ?: 0
                val p = soilDoc.getDouble("phosphorus")?.toInt() ?: 0
                val k = soilDoc.getDouble("potassium")?.toInt() ?: 0
                summary = summary.copy(
                    soilStatus = soilDoc.getString("status") ?: "N/A",
                    soilNPK = "N/P/K: $n / $p / $k kg/ha"
                )
            }

            // 2. CLIMATE DATA
            if (climateDoc.exists()) {
                val temp = climateDoc.getDouble("temp_avg") ?: 0.0
                val precip = climateDoc.getDouble("precip_24h") ?: 0.0
                summary = summary.copy(
                    climateTemp = String.format("%.1f°C", temp),
                    climatePrecip = String.format("%.1f mm (24h)", precip)
                )
            }

            // 3. YIELD DATA
            if (yieldDoc.exists()) {
                summary = summary.copy(
                    yieldAlert = yieldDoc.getString("reductionAlert") ?: "None"
                )
            }
        } catch (e: Exception) {
            Log.e("ChatbotFragment", "Failed to fetch worker data: ${e.message}")
            // Return default/unknown summary
        }
        return summary
    }

    // --- END PHASE 14 IMPLEMENTATION: FETCHING WORKER DATA ---


    /**
     * Reconstructs the farm list and then calls setupChatContext with the default selected farm's details.
     * 🔥 UPDATED: Calls fetchLatestFarmData before setting up the context.
     */
    private fun loadProfileAndSetupChat() {
        if (!isAdded) return
        tvStatus.text = "Loading farm data for context..."

        val uid = auth.currentUser?.uid
        if (uid == null) {
            setupChatContext(null, "a general region", "N/A", PlotSummary("N/A","N/A","N/A","N/A","N/A")) // Pass dummy data
            return
        }

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

                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val userDistrict = prefs.getString("user_district", "a general region")!!

                val currentFields = mutableListOf<FarmField>()
                var currentPlotCoords = mutableListOf<Map<String, Any>>()
                var attributeIndex = 0

                // Plot Reconstruction Logic (UNCHANGED)
                for (coordMap in coordsMapList) {
                    val isSeparator = coordMap["separator"] == true
                    if (isSeparator) {
                        if (currentPlotCoords.isNotEmpty() && attributeIndex < plotNames.size) {
                            val anchorPoint = currentPlotCoords.first()
                            val name = plotNames.getOrNull(attributeIndex) ?: "Plot ${attributeIndex + 1}"
                            val crop = plotsCrops.getOrNull(attributeIndex) ?: "Unknown Crop"

                            currentFields.add(FarmField(name.replace(" ", "_"), name, crop,
                                anchorPoint["latitude"] as? Double ?: 0.0, anchorPoint["longitude"] as? Double ?: 0.0, userDistrict))
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
                        anchorPoint["latitude"] as? Double ?: 0.0, anchorPoint["longitude"] as? Double ?: 0.0, userDistrict))
                }

                if (currentFields.isEmpty() && primaryLat != 0.0) {
                    currentFields.add(FarmField("primary", "Primary Farm Location", primaryCrop, primaryLat, primaryLng, userDistrict))
                }

                farmList = currentFields.toList()
                val initialSelectedFarm = farmList.firstOrNull()

                withContext(Dispatchers.Main) {
                    selectedFarm = initialSelectedFarm
                    actFarmSelector.setText(selectedFarm?.name ?: getString(R.string.hint_select_farm), false)

                    // 1. Geocode the default plot's location (on main/IO thread via suspend)
                    val (district, state) = if (initialSelectedFarm != null) {
                        geocodePlotLocation(initialSelectedFarm)
                    } else {
                        Pair("a general region", "N/A")
                    }

                    // 2. 🔥 CRITICAL PHASE 14 STEP: Fetch live worker data for context
                    val summaryData = if (initialSelectedFarm != null) {
                        fetchLatestFarmData(uid, initialSelectedFarm.id)
                    } else {
                        PlotSummary("N/A", "N/A", "N/A", "N/A", "N/A")
                    }

                    // 3. Setup context and load history for the default plot
                    setupChatContext(selectedFarm, district, state, summaryData)
                    selectedFarm?.let { loadChatHistory(it.id) }
                }

            } catch (e: Exception) {
                Log.e("ChatbotFragment", "Failed to load farm list for context: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    setupChatContext(null, "a general region", "N/A", PlotSummary("N/A","N/A","N/A","N/A","N/A"))
                }
            }
        }
    }

    /**
     * Sets up the system instruction based on the currently selected farm's data.
     * 🔥 UPDATED: Now includes PlotSummary data in the System Instruction.
     */
    private fun setupChatContext(farm: FarmField?, geocodedDistrict: String, geocodedState: String, summary: PlotSummary) {

        val district = geocodedDistrict
        val state = geocodedState

        val crops = farm?.crop ?: "various local crops"
        val farmName = farm?.name ?: "N/A"
        val farmType = if (farmList.size > 1) "Multi-Plot Farm" else "Single Plot Farm"

        val userLangCode = LanguageHelper.getPersistedData(requireContext())

        val languageInstruction = when (userLangCode) {
            "hi" -> "Respond strictly and concisely in Hindi."
            "te" -> "Respond strictly and concisely in Telugu."
            "kn" -> "Respond strictly and concisely in Kannada."
            "ta" -> "Respond strictly and concisely in Tamil."
            else -> "Respond strictly and concisely in English."
        }

        // --- START PHASE 14: INJECTING CONTEXTUAL DATA ---
        val contextData = "Current Farm Status (for Plot: $farmName): " +
                "Soil Health: ${summary.soilStatus} (${summary.soilNPK}). " +
                "Climate: ${summary.climateTemp}, Precip: ${summary.climatePrecip}. " +
                "Yield Risk: ${summary.yieldAlert}. "
        // --- END PHASE 14: INJECTING CONTEXTUAL DATA ---


        systemInstructionPrompt = "You are Framer Assistant, a helpful, knowledgeable, and reliable agricultural expert. " +
                "$languageInstruction " +
                "Your answers must be brief, focused, and tailored to local knowledge. " +
                "The farmer's profile for the selected plot is: Plot Name: $farmName. Location: $district, $state. " +
                "Farm Type: $farmType. Primary Crop: $crops. " +
                // 🔥 CRITICAL INJECTION POINT
                "**$contextData** " +
                "When providing advice, prioritize solutions relevant to this specific plot's crop and location (e.g., recommend local Mandi prices, specific regional diseases, or appropriate local weather actions). " +
                "Also, be prepared to suggest **crop rotation alternatives** based on the current soil health. " +
                "Start your reply with a friendly emoji or phrase. "

        isProfileLoaded = true
        if (view != null) {
            tvStatus.text = "Ready to chat (Context: ${farmName}, $district, $state, Crops: $crops)"

            if (arguments?.getString("initial_prompt") == null && conversationHistory.isEmpty() && chat.text.isEmpty()) {
                chat.append("🤖 Framer Assistant: ${getString(R.string.chatbot_greeting_user)}\n\n")
            }
        }
    }

    /**
     * Opens a dialog to select the active farm plot.
     * 🔥 UPDATED: Now fetches live worker data when a new plot is selected.
     */
    private fun showPlotSelectionDialog() {
        if (farmList.isEmpty()) {
            Toast.makeText(requireContext(), "No farm plots found to select.", Toast.LENGTH_SHORT).show()
            return
        }

        val farmNames = farmList.map { it.name }.toTypedArray()
        val checkedItemIndex = farmList.indexOfFirst { it.id == selectedFarm?.id }

        val oldFarmId = selectedFarm?.id
        val uid = auth.currentUser?.uid ?: return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.hint_select_farm))
            .setSingleChoiceItems(farmNames, checkedItemIndex) { dialog, which ->

                val selectedFarmName = farmNames[which]
                val newFarm = farmList.find { it.name == selectedFarmName }

                if (newFarm != null && selectedFarm?.id != newFarm.id) {

                    oldFarmId?.let { saveChatHistory(it) }
                    selectedFarm = newFarm
                    actFarmSelector.setText(newFarm.name, false)
                    tvStatus.text = "Fetching live context for ${newFarm.name}..."
                    clearConversationHistoryDisplay()

                    viewLifecycleOwner.lifecycleScope.launch {
                        // 1. Geocode
                        val (district, state) = geocodePlotLocation(newFarm)

                        // 2. 🔥 CRITICAL PHASE 14 STEP: Fetch live worker data
                        val summaryData = fetchLatestFarmData(uid, newFarm.id)

                        // 3. Setup new context and load history
                        setupChatContext(newFarm, district, state, summaryData)
                        loadChatHistory(newFarm.id)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun clearConversationHistoryDisplay() {
        chat.text = ""
    }


    private fun processInitialPrompt() {
        val initialPrompt = arguments?.getString("initial_prompt")

        if (initialPrompt != null && conversationHistory.isEmpty()) {

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {

                val loaded = withTimeoutOrNull(5000) {
                    while (!isProfileLoaded) {
                        Log.d("ChatbotFragment", "Waiting for profile context to load...")
                        delay(100)
                    }
                    true
                }

                if (loaded == true) {
                    Log.d("ChatbotFragment", "Profile context loaded. Sending initial prompt.")
                    sendMessage(initialPrompt)
                    arguments?.remove("initial_prompt")
                } else {
                    Log.e("ChatbotFragment", "Timeout waiting for profile context. Sending prompt without context.")
                    Toast.makeText(context, "Farm context failed to load quickly. Sending message anyway.", Toast.LENGTH_LONG).show()
                    isProfileLoaded = true
                    sendMessage(initialPrompt)
                    arguments?.remove("initial_prompt")
                }
            }
        }
    }

    private fun sendMessage(query: String) {
        chat.append("👨‍🌾 You: $query\n")
        chat.append("🤖 Framer Assistant: Thinking...\n")
        scrollToBottom()

        val messageToSend = if (conversationHistory.isEmpty()) {
            systemInstructionPrompt + "Here is the user's query: " + query
        } else {
            query
        }

        conversationHistory.add(Content(ROLE_USER, messageToSend))


        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reply = fetchGeminiReply()

                if (view != null) {
                    withContext(Dispatchers.Main) {
                        removeLastLine()
                        chat.append("🤖 Framer Assistant: $reply\n\n")

                        conversationHistory.add(Content(ROLE_MODEL, reply))

                        selectedFarm?.let { saveChatHistory(it.id) }

                        send.isEnabled = true
                        scrollToBottom()
                    }
                } else {
                    Log.w("ChatbotFragment", "Gemini response received, but fragment view destroyed.")
                }
            } catch (e: Exception) {
                if (view != null) {
                    withContext(Dispatchers.Main) {
                        removeLastLine()
                        chat.append("🤖 Framer Assistant: API Error. Could not connect or receive a valid reply. (${e.message})\n\n")
                        send.isEnabled = true
                        Log.e("ChatbotFragment", "Gemini Call Failed: ${e.message}", e)
                    }
                } else {
                    Log.e("ChatbotFragment", "Gemini error occurred after view destruction: ${e.message}", e)
                }
            }
        }
    }

    private fun fetchGeminiReply(): String {
        if (geminiApiKey.isEmpty() || geminiApiKey == "MISSING_API_KEY") {
            throw IOException("Gemini API Key is missing.")
        }

        val contentsArray = JSONArray()
        conversationHistory.forEach { content ->
            val part = JSONObject().put("text", content.text)
            val partsArray = JSONArray().put(part)

            val contentObject = JSONObject().apply {
                put("role", content.role)
                put("parts", partsArray)
            }
            contentsArray.put(contentObject)
        }

        val jsonPayload = JSONObject().apply {
            put("contents", contentsArray)
        }.toString()

        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonPayload)

        val request = Request.Builder()
            .url(modelUrl)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "No error body."
                throw IOException("HTTP ${response.code}. Error body: $errorBody")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response body.")
            val jsonResponse = JSONObject(responseBody)

            val candidates = jsonResponse.optJSONArray("candidates")

            if (candidates == null || candidates.length() == 0) {
                val promptFeedback = jsonResponse.optJSONObject("promptFeedback")?.toString() ?: "No candidates found."
                throw IOException("Model failed to generate a reply. Feedback: $promptFeedback")
            }

            val text = candidates.getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            return text
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun removeLastLine() {
        val currentText = chat.text.toString()
        val lastNewline = currentText.lastIndexOf('\n', currentText.length - 2)
        if (lastNewline >= 0) {
            chat.text = currentText.substring(0, lastNewline + 1)
        }
    }
}