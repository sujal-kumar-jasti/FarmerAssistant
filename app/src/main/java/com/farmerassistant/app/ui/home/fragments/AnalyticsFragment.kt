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
import androidx.lifecycle.lifecycleScope
import com.farmerassistant.app.R
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Calendar
import com.google.firebase.firestore.DocumentSnapshot
import java.util.concurrent.TimeUnit
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import android.Manifest
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.content.pm.PackageManager
import android.app.Activity
import java.util.Date
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import com.google.android.material.button.MaterialButton
import androidx.activity.result.contract.ActivityResultContracts

// 🔥 NEW PDF IMPORTS
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
// Data class to structure the calculated metrics
data class FarmMetrics(
    val plotCount: Int = 0,
    val totalDiseaseReports: Int = 0,
    val topDisease: String = "N/A",
    val avgYieldReduction: Double = 0.0,
    val recommendedCrop: String = "N/A",
    val yieldForecastDays: Int = 0
)

class AnalyticsFragment : Fragment() {

    private lateinit var tvAnalyticsStatus: TextView
    private lateinit var metricsContainer: LinearLayout
    private lateinit var tvYieldRec: TextView
    private lateinit var tvDiseaseSummary: TextView
    private lateinit var tvYieldReductionSummary: TextView
    private lateinit var btnExportReport: MaterialButton

    // Note: activeWebView is no longer needed for PDFDocument API, but keep the member variable if used elsewhere
    private var activeWebView: WebView? = null


    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "AnalyticsFragment"

    private var currentMetrics: FarmMetrics? = null

    // Permission launcher for storage (required for Android < 10)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Changed action to call direct PDF save function
        if (isGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentMetrics?.let { createAndSavePdf(it) }
        } else {
            Toast.makeText(
                context,
                "Storage permission denied. Cannot save PDF.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_analytics, container, false)

        tvAnalyticsStatus = v.findViewById(R.id.tvAnalyticsStatus)
        metricsContainer = v.findViewById(R.id.metricsContainer)
        tvYieldRec = v.findViewById(R.id.tvYieldRec)
        tvDiseaseSummary = v.findViewById(R.id.tvDiseaseSummary)
        tvYieldReductionSummary = v.findViewById(R.id.tvYieldReductionSummary)
        btnExportReport = v.findViewById(R.id.btnExportReport)

        loadAllReportsAndCalculateMetrics()

        btnExportReport.setOnClickListener {
            checkStoragePermissionAndExport()
        }

        return v
    }

    private fun checkStoragePermissionAndExport() {
        if (currentMetrics == null) {
            Toast.makeText(context, "Data not loaded yet. Please wait.", Toast.LENGTH_SHORT).show()
            return
        }

        // Check if runtime permission is needed (Android 10/Q and below)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // Permission granted or not needed (Android 10+ uses Scoped Storage/Downloads folder)
            currentMetrics?.let { createAndSavePdf(it) }
        }
    }

    private fun loadAllReportsAndCalculateMetrics() {
        val uid = auth.currentUser?.uid ?: return

        tvAnalyticsStatus.text = "Fetching all historical reports..."

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch Disease Reports
                val diseaseReports = db.collection("farmer_disease_reports")
                    .whereEqualTo("uid", uid)
                    .limit(100)
                    .get().await().documents

                // 2. Fetch Yield Predictions (Last 30 days)
                val oneMonthAgo =
                    Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
                val yieldReports = db.collection("yield_predictions")
                    .whereEqualTo("uid", uid)
                    .whereGreaterThan("timestamp", oneMonthAgo)
                    .limit(30)
                    .get().await().documents

                // 3. Fetch User Profile (for plot count)
                val userDoc = db.collection("users").document(uid).get().await()

                @Suppress("UNCHECKED_CAST")
                val plotCount =
                    (userDoc.get("farm_data") as? Map<String, Any>)?.get("plots_names") as? List<String>
                val totalPlots = plotCount?.size ?: 1

                val metrics = calculateMetrics(diseaseReports, yieldReports, totalPlots)

                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        currentMetrics = metrics
                        displayMetrics(metrics)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load analytics data: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    tvAnalyticsStatus.text = "Error: Could not load historical data."
                }
            }
        }
    }

    private fun calculateMetrics(
        diseaseReports: List<DocumentSnapshot>,
        yieldReports: List<DocumentSnapshot>,
        totalPlots: Int
    ): FarmMetrics {

        // --- Disease Analysis ---
        val diseaseFrequency = diseaseReports.groupingBy {
            it.getString("crop_disease")?.split(" (")?.firstOrNull()?.trim() ?: "Unknown"
        }.eachCount()

        val topDiseaseEntry = diseaseFrequency.maxByOrNull { it.value }
        val topDisease = if (diseaseReports.isNotEmpty()) {
            "${topDiseaseEntry?.key} (${topDiseaseEntry?.value} Reports)"
        } else {
            "None Reported"
        }

        // --- Yield Analysis ---
        val reductionRates = yieldReports.mapNotNull { doc ->
            val alert = doc.getString("reductionAlert")
            alert?.let { Regex("([0-9]+)%").find(it)?.groupValues?.get(1)?.toDouble() }
        }

        val avgReduction = if (reductionRates.isNotEmpty()) reductionRates.average() else 0.0

        // --- Recommendation (Simulated Crop Rotation) ---
        var recommendedCrop = "Maintain current crop"
        if (topDiseaseEntry?.key?.contains("rot", ignoreCase = true) == true) {
            recommendedCrop = "Consider rotating to pulses or a non-susceptible crop next cycle."
        }

        // --- Yield Forecast Date ---
        val latestForecastTimestamp =
            yieldReports.maxOfOrNull { it.getLong("timestamp") ?: 0L } ?: 0L
        val daysSinceForecast = if (latestForecastTimestamp > 0) {
            TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - latestForecastTimestamp)
                .toInt()
        } else {
            -1
        }


        return FarmMetrics(
            plotCount = totalPlots,
            totalDiseaseReports = diseaseReports.size,
            topDisease = topDisease,
            avgYieldReduction = avgReduction,
            recommendedCrop = recommendedCrop,
            yieldForecastDays = daysSinceForecast
        )
    }

    private fun displayMetrics(metrics: FarmMetrics) {
        if (!isAdded) return

        tvAnalyticsStatus.text = "Analytics ready for ${metrics.plotCount} plots."

        // --- Yield Recommendation ---
        tvYieldRec.text = metrics.recommendedCrop
        if (metrics.recommendedCrop.contains("rotate", ignoreCase = true)) {
            tvYieldRec.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.md_theme_error
                )
            )
        } else {
            tvYieldRec.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.md_theme_onSurfaceVariant
                )
            )
        }

        // --- Disease Summary ---
        tvDiseaseSummary.text =
            "Total Reports: ${metrics.totalDiseaseReports}. Top Threat: ${metrics.topDisease}"

        // --- Yield Reduction Summary ---
        val reductionText = if (metrics.avgYieldReduction > 0) {
            "Average Predicted Reduction (Last 30 Days): <b>${
                String.format(
                    "%.1f",
                    metrics.avgYieldReduction
                )
            }%</b>"
        } else {
            "No significant yield reduction predicted recently."
        }
        tvYieldReductionSummary.text =
            HtmlCompat.fromHtml(reductionText, HtmlCompat.FROM_HTML_MODE_LEGACY)

        // --- Dynamic Dashboard Cards ---
        metricsContainer.removeAllViews()

        // Card 1: Total Plots
        addMetricCard("Total Plots Monitored", metrics.plotCount.toString(), R.drawable.ic_map_pin)

        // Card 2: Days Since Last Forecast
        val forecastStatus = if (metrics.yieldForecastDays >= 0) {
            "${metrics.yieldForecastDays} Days Ago"
        } else {
            "N/A"
        }
        addMetricCard("Last Yield Forecast", forecastStatus, R.drawable.ic_yield)

        // Card 3: Historical Disease Density (Simulated as count/plot)
        val density =
            metrics.totalDiseaseReports.toDouble() / if (metrics.plotCount > 0) metrics.plotCount.toDouble() else 1.0
        val diseaseDensity = if (metrics.totalDiseaseReports > 0) {
            String.format(Locale.US, "%.2f / plot", density)
        } else {
            "0"
        }
        addMetricCard("Avg. Disease Density", diseaseDensity, R.drawable.ic_alert_bug)

    }

    private fun addMetricCard(title: String, value: String, iconResId: Int) {
        val card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_analytics_metric, metricsContainer, false) as MaterialCardView
        card.findViewById<TextView>(R.id.tvMetricTitle).text = title
        card.findViewById<TextView>(R.id.tvMetricValue).text = value
        card.findViewById<ImageView>(R.id.ivMetricIcon)?.setImageResource(iconResId)
        metricsContainer.addView(card)
    }

    // --- PHASE 20: DIRECT PDF DRAWING LOGIC (Reliable Implementation) ---

    // This is the function called directly after permission check
    private fun createAndSavePdf(metrics: FarmMetrics) {
        if (!isAdded) return

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size (approx 595x842 pts)
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()

        var x = 30f
        var y = 40f
        val lineHeight = 20f

        // --- 1. Header ---
        paint.color = Color.rgb(30, 136, 229) // Blue for primary color
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 20f
        canvas.drawText("Farm Performance Report", x, y, paint)
        y += lineHeight * 2

        paint.color = Color.GRAY
        paint.textSize = 10f
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        canvas.drawText("Generated: ${dateFormat.format(Date())}", x, y, paint)
        y += lineHeight * 2

        // --- 2. Key Metrics Section ---
        paint.textSize = 14f
        paint.color = Color.BLACK
        canvas.drawText("Key Performance Indicators:", x, y, paint)
        y += lineHeight

        paint.textSize = 12f

        // Plot Count
        canvas.drawText("Plots Monitored:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("${metrics.plotCount}", x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight

        // Days Since Forecast
        canvas.drawText("Last Forecast:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("${metrics.yieldForecastDays} Days Ago", x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight

        // Avg. Disease Density
        val density = metrics.totalDiseaseReports.toDouble() / if (metrics.plotCount > 0) metrics.plotCount.toDouble() else 1.0
        canvas.drawText("Avg. Disease Density:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText(String.format(Locale.US, "%.2f / plot", density), x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight * 2

        // --- 3. Disease Summary ---
        paint.textSize = 14f
        paint.color = Color.BLACK
        canvas.drawText("Disease & Yield Summary:", x, y, paint)
        y += lineHeight

        paint.textSize = 12f
        canvas.drawText("Total Reports:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("${metrics.totalDiseaseReports}", x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight

        canvas.drawText("Top Threat:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText(metrics.topDisease, x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight

        paint.color = if (metrics.avgYieldReduction > 0) Color.RED else Color.rgb(0, 121, 107) // Red for reduction
        canvas.drawText("Avg. Yield Reduction:", x, y, paint.apply { typeface = Typeface.DEFAULT_BOLD })
        canvas.drawText("${String.format("%.1f", metrics.avgYieldReduction)}%", x + 150, y, paint.apply { typeface = Typeface.DEFAULT })
        y += lineHeight * 2


        // --- 4. AI Recommendation ---
        paint.textSize = 14f
        paint.color = Color.BLACK
        canvas.drawText("AI Recommendation:", x, y, paint)
        y += lineHeight

        paint.color = if (metrics.recommendedCrop.contains("rotate", true)) Color.RED else Color.rgb(0, 121, 107)
        paint.textSize = 12f
        canvas.drawText(metrics.recommendedCrop, x, y, paint)
        y += lineHeight

        document.finishPage(page)

        // --- SAVE THE FILE ---
        val fileName = "FarmReport_${System.currentTimeMillis()}.pdf"

        // Use standard Downloads directory
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)

        try {
            document.writeTo(FileOutputStream(file))
            Toast.makeText(context, "PDF saved successfully to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(context, "Error saving PDF: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "PDF Write Failed: ${e.message}", e)
        } finally {
            document.close()
        }
    }


    // The generateReportHtml function is no longer used for printing, but we keep it for reference or potential web view display.
    private fun generateReportHtml(metrics: FarmMetrics): String {
        val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        val date = dateFormat.format(Date())

        return """
            <html>
            <head>
                <style>
                    body { font-family: sans-serif; padding: 20px; color: #333; }
                    h1 { color: #1E88E5; border-bottom: 2px solid #EEE; padding-bottom: 5px; }
                    .section { margin-top: 25px; padding: 15px; border: 1px solid #CCC; border-radius: 8px; }
                    .metric { margin-bottom: 10px; }
                    .label { font-weight: bold; width: 180px; display: inline-block; }
                    .value { color: #00796B; }
                    .alert { color: ${
            if (metrics.recommendedCrop.contains(
                    "rotate",
                    true
                )
            ) "#D32F2F" else "#00796B"
        }; font-weight: bold; margin-top: 10px;}
                </style>
            </head>
            <body>
                <h1>Farm Performance Report - ${date}</h1>
                <p>Report Generated by Farmer Assistant App.</p>

                <div class="section">
                    <h2>Key Performance Indicators</h2>
                    <div class="metric"><span class="label">Plots Monitored:</span> <span class="value">${metrics.plotCount}</span></div>
                    <div class="metric"><span class="label">Last Forecast:</span> <span class="value">${metrics.yieldForecastDays} Days Ago</span></div>
                    <div class="metric"><span class="label">Avg. Disease Density:</span> <span class="value">${
            String.format(
                Locale.US,
                "%.2f / plot",
                metrics.totalDiseaseReports.toDouble() / if (metrics.plotCount > 0) metrics.plotCount.toDouble() else 1.0
            )
        }</span></div>
                </div>

                <div class="section">
                    <h2>Yield & Disease Summary (Last 30 Days)</h2>
                    <div class="metric"><span class="label">Total Disease Reports:</span> <span class="value">${metrics.totalDiseaseReports}</span></div>
                    <div class="metric"><span class="label">Top Threat:</span> <span class="value">${metrics.topDisease}</span></div>
                    <div class="metric"><span class="label">Avg. Yield Reduction:</span> <span class="value">${
            String.format(
                "%.1f",
                metrics.avgYieldReduction
            )
        }%</span></div>
                </div>

                <div class="section">
                    <h2>AI Recommendation</h2>
                    <p class="alert">${metrics.recommendedCrop}</p>
                    <p style="margin-top: 20px;">*The full report includes detailed plot-level data and market insights (requires premium subscription in a real app).</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    // Note: The printReportToPdf function from the previous steps is replaced entirely by createAndSavePdf.
    // The previous implementation was unstable and is no longer needed.

    override fun onDestroyView() {
        super.onDestroyView()
        // No WebView cleanup is strictly needed here as the direct PDF save method doesn't rely on WebView lifecycle.
        // However, if the activeWebView member is used in the future, it's safer to null it out.
        activeWebView = null
    }
}