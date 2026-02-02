package com.farmerassistant.app.ui.home.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.farmerassistant.app.R
import androidx.preference.PreferenceManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import android.location.Geocoder
import com.google.gson.Gson
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import java.text.SimpleDateFormat
import java.util.Date
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import com.google.android.material.card.MaterialCardView
import android.widget.ImageView
import com.google.firebase.firestore.DocumentSnapshot
import android.graphics.PorterDuff
// Data class to hold all three price points
data class MandiPriceData(val modalPrice: Int, val minPrice: Int, val maxPrice: Int)

// Data class for the farmer's crop listing (FIXED for Firestore deserialization)
data class CropListing(
    val crop: String = "",
    val quantity: String = "",
    val preferredPrice: Int = 0,
    val datePosted: Long = 0,
    val farmerUid: String = "",
    @Transient // Firestore will ignore this field during mapping
    val documentId: String = ""
)

class MarketFragment : Fragment() {
    private lateinit var tvMarket: TextView
    private lateinit var priceContainer: LinearLayout
    private lateinit var btnListForSale: MaterialButton
    private lateinit var listingsContainer: LinearLayout
    private lateinit var buyerInterestContainer: LinearLayout // 🔥 NEW BINDING

    private val client = OkHttpClient()
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val gson = Gson()

    private val OGD_RESOURCE_ID = "9ef84268-d588-465a-a308-a864a43d0070"
    private val OGD_BASE_URL = "https://api.data.gov.in/resource/$OGD_RESOURCE_ID?"
    private val OGD_API_KEY = "579b464db66ec23bdd00000102e00c78686b438c6e4b55f6c37d407e"
    private val LISTINGS_COLLECTION = "crop_listings"

    // Define commodity icons and Mock Historical Data
    private val commodityIcons = mapOf(
        "Wheat" to "🌾", "Paddy" to "🍚", "Maize" to "🌽", "Mustard" to "🌿",
        "Cotton" to "☁️", "Sugarcane" to "🌱", "Chilli" to "🌶️", "Tomato" to "🍅",
        "Onion" to "🧅", "Lemon" to "🍋", "Ginger(Green)" to "🫚", "Potato" to "🥔",
        "Papaya" to "🥭", "Guar" to "🫘", "Bitter gourd" to "🥒", "Cucumbar(Kheera)" to "🥒",
        "Black Gram" to "⚫", "Ground Nut" to "🥜", "Cummin Seed" to "🌿"
    )

    private var targetCrop: String = "Paddy"
    private var farmerCropList: List<String> = emptyList()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_market, container, false)
        tvMarket = v.findViewById(R.id.marketText)
        priceContainer = v.findViewById(R.id.priceContainer)
        btnListForSale = v.findViewById(R.id.btnListForSale)
        listingsContainer = v.findViewById(R.id.listingsContainer)
        buyerInterestContainer = v.findViewById(R.id.buyerInterestContainer)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvMarket.text = "Loading data..."
        priceContainer.removeAllViews()

        targetCrop = arguments?.getString("target_crop") ?: "Paddy"

        btnListForSale.setOnClickListener {
            showCreateListingDialog()
        }

        loadFarmerCrops().invokeOnCompletion {
            if (isAdded) {
                loadLocationAndFetchMarketData()
                loadCurrentListings()
            }
        }
    }

    // --- LOAD FARMER CROPS ---
    private fun loadFarmerCrops() = viewLifecycleOwner.lifecycleScope.launch {
        val uid = auth.currentUser?.uid ?: return@launch
        try {
            val userDoc = db.collection("users").document(uid).get().await()
            val uniqueCrops = mutableSetOf<String>()

            userDoc.getString("crops_grown")?.split(",")?.map { it.trim() }
                ?.filter { it.isNotEmpty() }?.forEach { uniqueCrops.add(it) }

            @Suppress("UNCHECKED_CAST")
            val farmData = userDoc.get("farm_data") as? Map<String, Any>
            farmData?.let {
                (it["plots_crops"] as? List<String>)?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?.forEach { uniqueCrops.add(it) }
            }

            farmerCropList = uniqueCrops.toList().sorted().filter { it.isNotEmpty() }
            if (farmerCropList.isEmpty()) farmerCropList = listOf(targetCrop)

        } catch (e: Exception) {
            Log.e("MarketFragment", "Failed to load farmer crops: ${e.message}")
            farmerCropList = listOf(targetCrop)
        }
    }
    // --- END LOAD FARMER CROPS ---

    // --- DIALOG TO CREATE LISTING ---
    private fun showCreateListingDialog() {
        if (!isAdded) return
        if (farmerCropList.isEmpty()) {
            Toast.makeText(
                context,
                "Cannot list crops: Farm crop list is empty.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_list_crop, null)

        val actCrop = dialogView.findViewById<AutoCompleteTextView>(R.id.actCrop)
        val etQuantity = dialogView.findViewById<TextInputEditText>(R.id.etQuantity)
        val etPrice = dialogView.findViewById<TextInputEditText>(R.id.etPrice)

        val cropAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            farmerCropList
        )
        actCrop.setAdapter(cropAdapter)
        actCrop.setText(targetCrop, false)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_title_list_crop))
            .setView(dialogView)
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.dialog_list) { dialog, _ ->
                val selectedCrop = actCrop.text.toString().trim()
                val quantity = etQuantity.text.toString().trim()
                val price = etPrice.text.toString().trim().toIntOrNull()

                if (selectedCrop.isEmpty() || quantity.isEmpty() || price == null || price <= 0) {
                    Toast.makeText(
                        context,
                        "All fields are required and Price must be valid.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    submitCropListing(selectedCrop, quantity, price)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun submitCropListing(crop: String, quantity: String, price: Int) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(context, "Authentication required to list crops.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val listingData = CropListing(
            crop = crop,
            quantity = quantity,
            preferredPrice = price,
            datePosted = System.currentTimeMillis(),
            farmerUid = uid
        )

        db.collection(LISTINGS_COLLECTION)
            .add(listingData)
            .addOnSuccessListener {
                Toast.makeText(
                    context,
                    "Listing for $quantity of $crop posted successfully!",
                    Toast.LENGTH_LONG
                ).show()
                loadCurrentListings()
            }
            .addOnFailureListener { e ->
                Log.e("MarketFragment", "Failed to submit listing: ${e.message}")
                Toast.makeText(context, "Error submitting listing.", Toast.LENGTH_LONG).show()
            }
    }
    // --- END DIALOG TO CREATE LISTING ---

    // --- IMPLEMENT DELETE LISTING LOGIC ---

    private fun deleteListing(listing: CropListing) {
        if (listing.documentId.isEmpty()) {
            Toast.makeText(context, "Error: Listing ID is missing.", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage("Are you sure you want to delete the listing for ${listing.crop} (${listing.quantity})?")
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setPositiveButton("Delete") { _, _ ->
                db.collection(LISTINGS_COLLECTION).document(listing.documentId)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(context, "Listing deleted successfully.", Toast.LENGTH_SHORT)
                            .show()
                        loadCurrentListings() // Refresh the list
                    }
                    .addOnFailureListener { e ->
                        Log.e("MarketFragment", "Deletion failed: ${e.message}")
                        Toast.makeText(context, "Failed to delete listing.", Toast.LENGTH_SHORT)
                            .show()
                    }
            }
            .show()
    }

    // --- LOAD CURRENT LISTINGS (Updated to use Document ID and tag container) ---
    private fun loadCurrentListings() {
        val uid = auth.currentUser?.uid
        if (uid == null || !isAdded) return

        listingsContainer.removeAllViews()

        val tvLoading = TextView(context).apply {
            text = "Loading your active listings..."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.md_theme_onSurfaceVariant
                )
            )
            setPadding(0, 10, 0, 10)
        }
        listingsContainer.addView(tvLoading)

        db.collection(LISTINGS_COLLECTION)
            .whereEqualTo("farmerUid", uid)
            .orderBy("datePosted", Query.Direction.DESCENDING)
            .limit(5)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (!isAdded) return@addOnSuccessListener
                listingsContainer.removeAllViews()

                val listings = querySnapshot.documents.mapNotNull { doc ->
                    doc.toObject(CropListing::class.java)
                        ?.copy(documentId = doc.id) // Capture document ID and append it
                }

                listingsContainer.tag = listings // Store listings for buyer interest calculation
                displayCurrentListings(listings)
                loadBuyerInterest() // Refresh buyer interest after listings are loaded

            }
            .addOnFailureListener { e ->
                Log.e("MarketFragment", "Error loading listings: ${e.message}")
                if (isAdded) {
                    listingsContainer.removeAllViews()
                    TextView(context).apply {
                        text = "Error loading listings: Check Firebase Index."
                        setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.md_theme_error
                            )
                        )
                        setPadding(0, 10, 0, 10)
                    }.also { listingsContainer.addView(it) }
                }
            }
    }

    private fun displayCurrentListings(listings: List<CropListing>) {
        if (!isAdded) return

        if (listings.isEmpty()) {
            val tvNoListings = TextView(context).apply {
                text = "No active listings found. Tap 'List Crop' to start selling."
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.md_theme_onSurfaceVariant
                    )
                )
                setPadding(0, 10, 0, 10)
            }
            listingsContainer.addView(tvNoListings)
            return
        }

        val dateFormatter = SimpleDateFormat("dd MMM YYYY", Locale.getDefault())


            val colorSurface = ContextCompat.getColor(requireContext(), R.color.md_theme_surface)
            val colorPrimary = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
            val colorOnSurface = ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface)
            val colorOutlineVariant = ContextCompat.getColor(requireContext(), R.color.md_theme_outlineVariant)
            val colorError = ContextCompat.getColor(requireContext(), R.color.md_theme_error)

        listings.forEach { listing ->
            val icon = commodityIcons[listing.crop.split(" ").first()] ?: "🌱"
            val dateString = dateFormatter.format(Date(listing.datePosted))

            // --- UI Cleanup: MaterialCardView for separation and better look ---
            val card = MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dpToPx()
                }
                cardElevation = 0.dpToPx().toFloat()
                radius = 8.dpToPx().toFloat()
                setCardBackgroundColor(colorSurface)
                strokeWidth = 1.dpToPx()
                strokeColor = colorOutlineVariant
            }

            // Inner layout to hold text and delete icon
            val innerLayout = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                // FIX for padding error: Ensure all four parameters are passed to setPadding
                setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Text content (Crop, Price, Quantity, Date)
            val detailsView = TextView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                val priceHtml =
                    "<font color='$colorPrimary'><b>₹${listing.preferredPrice}/Qtl</b></font>"

                val detailsHtml = "<b>$icon ${listing.crop}</b>" +
                        "<br>Price: $priceHtml" +
                        "<br><small>Qty: ${listing.quantity} | Posted: ${dateString}</small>"

                text = HtmlCompat.fromHtml(detailsHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
                setTextColor(colorOnSurface)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            }

            // Delete Icon (ImageView)
            val deleteIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(30.dpToPx(), 30.dpToPx()).apply {
                    marginStart = 8.dpToPx()
                }
                setImageResource(R.drawable.ic_delete)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundResource(R.drawable.bg_circle_ripple)

                setOnClickListener {
                    deleteListing(listing)
                }
                contentDescription = "Delete Listing for ${listing.crop}"
                // Apply a subtle error tint
                setColorFilter(colorError, PorterDuff.Mode.SRC_IN)
            }

            innerLayout.addView(detailsView)
            innerLayout.addView(deleteIcon)
            card.addView(innerLayout)
            listingsContainer.addView(card)
        }
    }

    // --- NEW: Load Buyer Interest (Simulated Matching) ---
    private fun loadBuyerInterest() {
        // Retrieve listings from the tag we stored after fetching them
        @Suppress("UNCHECKED_CAST")
        val listings = listingsContainer.tag as? List<CropListing> ?: emptyList()

        buyerInterestContainer.removeAllViews()

        // Define colors once
        val colorPrimary = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
        val colorOnSurfaceVariant =
            ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant)
        val colorError = ContextCompat.getColor(requireContext(), R.color.md_theme_error)

        if (listings.isEmpty()) {
            val tvNoInterest = TextView(context).apply {
                text = "No active listings, no buyer interest to track."
                setTextColor(colorOnSurfaceVariant)
                setPadding(0, 10, 0, 10)
            }
            buyerInterestContainer.addView(tvNoInterest)
            return
        }

        var matchesFound = 0

        // 🔥 Define the desired icon size (e.g., 24dp)
        val iconSizePx = 24.dpToPx()

        listings.forEachIndexed { index, listing ->
            // Simulate finding a bulk buyer nearby (50% chance)
            val isUrgentMatch = listing.preferredPrice < 5000

            if (index % 2 == 0 || isUrgentMatch) {
                val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sell)?.apply {
                    setBounds(0, 0, iconSizePx, iconSizePx) // Set fixed bounds (width, height)
                }

                val matchView = TextView(context).apply {
                    val message = if (isUrgentMatch) {
                        "🚨 URGENT BUYER ALERT:  High demand for ${listing.crop} near your region."
                    } else {
                        "🎯  BULK ORDER MATCH:  Buyer found for ${listing.quantity} of ${listing.crop} ."
                    }

                    text = HtmlCompat.fromHtml(message, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    setTextColor(if (isUrgentMatch) colorError else colorPrimary)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)

                    // 🔥 CRITICAL FIX: Use setCompoundDrawables (NOT setCompoundDrawablesWithIntrinsicBounds)
                    // The parameters are: left, top, right, bottom. We only want the left drawable.
                    setCompoundDrawables(drawable, null, null, null)

                    compoundDrawablePadding = 12.dpToPx()
                    setPadding(0, 10, 0, 10)
                }
                buyerInterestContainer.addView(matchView)
                matchesFound++
            }
        }

        if (matchesFound == 0) {
            val tvNoInterest = TextView(context).apply {
                text = "No immediate buyer matches for your active listings."
                setTextColor(colorOnSurfaceVariant)
                setPadding(0, 10, 0, 10)
            }
            buyerInterestContainer.addView(tvNoInterest)
        }
    }
    // --- END NEW: Load Buyer Interest ---


    // Helper function to convert DP to PX (copied from ClimateFragment)
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density + 0.5f).toInt()
    }


    // MASTER FUNCTION: Loads location from Fragment Arguments or Firestore.
    private fun loadLocationAndFetchMarketData() {
        if (!isAdded) return

        // 1. CHECK FRAGMENT ARGUMENTS FIRST
        val args = arguments
        val argLat = args?.getDouble("market_lat") ?: 0.0
        val argLng = args?.getDouble("market_lng") ?: 0.0

        if (argLat != 0.0 || argLng != 0.0) {
            tvMarket.text = "Searching market prices for selected plot location..."
            priceContainer.removeAllViews()
            geocodeLocationAndFetch(argLat, argLng)
            return
        }

        // 2. FALLBACK TO FIRESTORE (If no arguments were passed)
        val uid = auth.currentUser?.uid
        if (uid == null) {
            tvMarket.text = "Error: Authentication issue. Please log in again."
            loadCachedMarketData(isFallback = true)
            return
        }

        tvMarket.text = "Loading primary farm location from Firestore..."

        db.collection("users").document(uid).get().addOnSuccessListener { document ->
            if (!isAdded) {
                Log.w("MarketFragment", "Fragment detached, skipping Firestore result handling.")
                return@addOnSuccessListener
            }

            val lat = document.getDouble("lat") ?: 0.0
            val lng = document.getDouble("lng") ?: 0.0

            tvMarket.text = "Searching market prices..."
            priceContainer.removeAllViews()

            geocodeLocationAndFetch(lat, lng)

        }.addOnFailureListener {
            if (isAdded) {
                tvMarket.text = "Error loading primary location. Loading cache."
                Log.e("MarketFragment", "Firestore fetch failed: ${it.message}")
            }
            loadCachedMarketData(isFallback = true)
        }
    }

    // FUNCTION: Geocodes Lat/Lng to State/District and calls the API
    private fun geocodeLocationAndFetch(lat: Double, lng: Double) {
        if (!isAdded) return

        val defaultLocaleGeocoder = Geocoder(requireContext(), Locale.getDefault())
        val englishGeocoder = Geocoder(requireContext(), Locale.ENGLISH)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var locationFilters = ""
            var locationDescription = "National"
            var apiStateName = ""
            var userStateDefaultLocale = ""

            try {
                @Suppress("DEPRECATION")
                val addressesDefault = if (lat != 0.0 || lng != 0.0) {
                    defaultLocaleGeocoder.getFromLocation(lat, lng, 1)
                } else {
                    null
                }

                @Suppress("DEPRECATION")
                val addressesEnglish = if (lat != 0.0 || lng != 0.0) {
                    englishGeocoder.getFromLocation(lat, lng, 1)
                } else {
                    null
                }

                if (addressesDefault != null && addressesDefault.isNotEmpty()) {
                    val address = addressesDefault[0]
                    userStateDefaultLocale = address.adminArea ?: ""
                }

                if (addressesEnglish != null && addressesEnglish.isNotEmpty()) {
                    val address = addressesEnglish[0]
                    apiStateName = address.adminArea ?: ""
                }

                if (apiStateName.length > 2) {
                    locationFilters += "&filters%5Bstate.keyword%5D=${
                        apiStateName.replace(
                            " ",
                            "%20"
                        )
                    }"
                    locationDescription = userStateDefaultLocale
                }

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        val statusText = if (apiStateName.isNotEmpty()) {
                            "Fetching live prices for ${locationDescription} (State-level)..."
                        } else {
                            "Fetching live prices for National markets..."
                        }
                        tvMarket.text = statusText
                    }
                }

                val baseFilters = "api-key=$OGD_API_KEY&format=json&offset=0&limit=50"
                val apiUrl = OGD_BASE_URL + baseFilters + locationFilters

                val request = Request.Builder().url(apiUrl).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP Code: ${response.code}. Failed to reach Mandi API.")

                    val responseBody =
                        response.body?.string() ?: throw IOException("Empty API response.")

                    val (marketDate, marketData) = parseMandiJson(responseBody)

                    if (marketData.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                tvMarket.text =
                                    "No live data for ${locationDescription}. Loading cached prices."
                            }
                            loadCachedMarketData(isFallback = true)
                        }
                        return@use
                    }

                    saveCachedMarketData(responseBody)

                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            updateMarketUI(marketData, marketDate)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(
                    "MarketFragment",
                    "Market API/Geocoder failed: ${e.message}. Attempting cache load.",
                    e
                )

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvMarket.visibility = View.VISIBLE
                        tvMarket.text =
                            "⚠️ NETWORK ERROR: Could not retrieve live data for ${locationDescription}. Loading cached prices."
                        loadCachedMarketData(isFallback = true)
                    }
                }
            } catch (e: Exception) {
                Log.e("MarketFragment", "Geocoder/Parsing failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        tvMarket.visibility = View.VISIBLE
                        tvMarket.text =
                            "⚠️ DATA ERROR: Failed to process market data. Loading cache."
                        loadCachedMarketData(isFallback = true)
                    }
                }
            } catch (e: CancellationException) {
                Log.d("MarketFragment", "Coroutine cancelled silently.")
            }
        }
    }


    // Function to save market data to SharedPreferences (Caching)
    private fun saveCachedMarketData(currentJson: String) {
        if (!isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val previousDayJson = prefs.getString("cached_market_data", null)

        if (previousDayJson != null) {
            prefs.edit().putString("previous_day_market_data", previousDayJson).apply()
        }

        prefs.edit().putString("cached_market_data", currentJson).apply()
        Log.d("MarketFragment", "Market data cached. Previous day's data updated.")
    }

    // Function to load market data from SharedPreferences (Offline Support)
    private fun loadCachedMarketData(isFallback: Boolean = false) {
        if (!isAdded) return

        priceContainer.removeAllViews()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val cachedJson = prefs.getString("cached_market_data", null)

        if (cachedJson != null) {
            try {
                val (marketDate, marketData) = parseMandiJson(cachedJson)

                if (marketData.isEmpty()) {
                    tvMarket.text = "No live data and cached data is empty/corrupt."
                    tvMarket.visibility = View.VISIBLE
                    return
                }

                updateMarketUI(marketData, marketDate)

                val statusText = if (isFallback) {
                    "Market Data (CACHED: ${marketDate} prices). Live data failed or was empty."
                } else {
                    "Market Data (OFFLINE: ${marketDate} prices). Prices may be outdated."
                }

                tvMarket.text = statusText
                tvMarket.visibility = View.VISIBLE

            } catch (e: Exception) {
                Log.e("MarketFragment", "Error loading/parsing cached market data: ${e.message}")
                tvMarket.text = "No network. Cached data is corrupt or unavailable."
                tvMarket.visibility = View.VISIBLE
            }
        } else {
            tvMarket.text = if (isFallback) {
                "No live data and no cached market data available."
            } else {
                "No network connection and no cached market data available."
            }
            tvMarket.visibility = View.VISIBLE
        }
    }


    // Function to parse the LIVE OGD Mandi Price JSON response
    private fun parseMandiJson(jsonString: String): Pair<String, Map<String, MandiPriceData>> {
        val dataMap = mutableMapOf<String, MandiPriceData>()
        var marketDate = "N/A"
        try {
            val json = JSONObject(jsonString)

            marketDate = json.optString("updated_date", "N/A").split(" ").firstOrNull() ?: "N/A"

            if (!json.has("records") || json.optInt("count", 0) == 0) {
                Log.e("MarketFragment", "JSON response lacks 'records' or count is 0.")
                return Pair(marketDate, emptyMap())
            }

            val records = json.getJSONArray("records")
            val addedCommodities = mutableSetOf<String>()

            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val commodity = record.optString("commodity", "Unknown").trim()
                val market = record.optString("market", "").trim()

                val modalPrice = record.optString("modal_price", "0").toFloatOrNull()?.toInt() ?: 0
                val minPrice = record.optString("min_price", "0").toFloatOrNull()?.toInt() ?: 0
                val maxPriceString = record.optString("max_price", "0")
                val parsedMaxPrice = maxPriceString.toFloatOrNull()?.toInt() ?: 0

                val maxPrice = maxOf(parsedMaxPrice, modalPrice)

                val simpleCropName = commodity.split(" ").first()

                if (modalPrice > 0 && commodity.isNotEmpty() && !addedCommodities.contains(
                        simpleCropName
                    )
                ) {
                    val priceData = MandiPriceData(modalPrice, minPrice, maxPrice)
                    dataMap["$commodity ($market)"] = priceData
                    addedCommodities.add(simpleCropName)
                }
            }
            return Pair(marketDate, dataMap)
        } catch (e: Exception) {
            Log.e("MarketFragment", "JSON Parsing Error: ${e.message}")
            return Pair(marketDate, emptyMap())
        }
    }


    private fun updateMarketUI(data: Map<String, MandiPriceData>, marketDate: String) {
        if (!isAdded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val prevDayJson = prefs.getString("previous_day_market_data", null)
        val previousDayPrices = if (prevDayJson != null) {
            parseMandiJson(prevDayJson).second
        } else {
            emptyMap()
        }
        if (data.isEmpty()) {
            tvMarket.visibility = View.VISIBLE
            tvMarket.text =
                "No market data available for the region ($marketDate). Try again later or check API parameters."
            priceContainer.removeAllViews()
            return
        }

        priceContainer.removeAllViews()
        tvMarket.visibility = View.VISIBLE

        tvMarket.text = "Live Prices: Mandi Data as of ${marketDate}"

        // Define colors once
        val colorPrimary = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
        val colorError = ContextCompat.getColor(requireContext(), R.color.md_theme_error)
        val colorSecondary = ContextCompat.getColor(requireContext(), R.color.md_theme_secondary)
        val colorGreen = ContextCompat.getColor(requireContext(), R.color.whatsapp_green)
        val colorOnSurface = ContextCompat.getColor(requireContext(), R.color.md_theme_onSurface)
        val colorOnSurfaceVariant =
            ContextCompat.getColor(requireContext(), R.color.md_theme_onSurfaceVariant)


        data.forEach { (cropLabel, prices) ->

            val modalPrice = prices.modalPrice
            val minPrice = prices.minPrice
            val maxPrice = prices.maxPrice

            val cropName = cropLabel.split(" ").first()

            // Trend Calculation (compares Modal Price to Mock History)
            val historicalPrice = previousDayPrices[cropLabel]?.modalPrice
            val comparisonPrice = historicalPrice ?: modalPrice

            val (trendIcon, trendColorId) = when {
                modalPrice > comparisonPrice -> Pair("↑", colorPrimary)
                modalPrice < comparisonPrice -> Pair("↓", colorError)
                else -> Pair("—", colorSecondary)
            }
            val icon = commodityIcons[cropName] ?: "🌱"

            // --- Historical Price Recommendation (Phase 15 Completion) ---
            val (recommendationText, recColor) = when {
                modalPrice >= maxPrice -> Pair(
                    "SELL NOW (Market Peak)",
                    colorGreen
                ) // Price is at or above max observed price
                modalPrice <= minPrice -> Pair(
                    "HOLD/WAIT (Price Low)",
                    colorError
                ) // Price is at or below min observed price
                modalPrice > comparisonPrice -> Pair("Rising - Good Time to Sell", colorPrimary)
                else -> Pair("Stable - Monitor Next Trend", colorSecondary)
            }


            // 1. Crop Label TextView
            val cropLabelTextView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                text = "$icon $cropLabel"
                setTextColor(colorOnSurface)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                (layoutParams as ViewGroup.MarginLayoutParams).topMargin = 16
            }

            // 2. Modal Price and Trend TextView (Main Focus)
            val modalPriceTextView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                setTextColor(trendColorId)

                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)

                // Modal Price is the main focus, with the trend arrow
                val priceHtml = "₹${modalPrice} / Quintal (<b>$trendIcon</b>)"
                text = HtmlCompat.fromHtml(priceHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)

                (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 4
            }

            // 3. Min/Max Range Display
            val minMaxTextView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(colorOnSurfaceVariant)

                text = "Min: ₹${minPrice} | Max: ₹${maxPrice}"
                (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                    4 // Reduced bottom margin
            }

            // 🔥 Recommendation TextView
            val recommendationTextView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setTextColor(recColor)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                text = recommendationText
                (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin =
                    20 // Separation for next item
            }


            priceContainer.addView(cropLabelTextView)
            priceContainer.addView(modalPriceTextView)
            priceContainer.addView(minMaxTextView)
            priceContainer.addView(recommendationTextView)
        }
    }

}