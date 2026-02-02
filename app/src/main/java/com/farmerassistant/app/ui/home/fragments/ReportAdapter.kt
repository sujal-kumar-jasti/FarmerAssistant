package com.farmerassistant.app.ui.home.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.farmerassistant.app.R
import java.text.SimpleDateFormat
import java.util.*

// 1. Data Model for Firestore document (used here to display aggregated data)
data class RegionalReport(
    val crop: String = "",
    val disease: String = "", // Now holds "Disease (X Reports)"
    val severity: String = "Low",
    val timestamp: Long = 0
)

// 2. RecyclerView Adapter
class ReportAdapter(private var reports: List<RegionalReport>) :
    RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    private val dateFormatter = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    // 3. ViewHolder updated to include the severity indicator View
    inner class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvOutbreakTitle)
        val details: TextView = itemView.findViewById(R.id.tvOutbreakDetails)
        val indicator: View = itemView.findViewById(R.id.vSeverityIndicator) // Severity indicator bar
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_outbreak_report, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        val report = reports[position]

        val context = holder.itemView.context

        // --- 1. Handle "No reports" Case (Uses severity_none color) ---
        if (report.crop.isEmpty() && report.disease.contains("No recent outbreaks")) {
            holder.title.text = report.disease
            holder.details.text = ""
            val color = ContextCompat.getColor(context, R.color.severity_none)
            holder.indicator.setBackgroundColor(color)
            return
        }

        // --- 2. Format and Set Text ---
        val dateString = dateFormatter.format(Date(report.timestamp))

        // Clean up the crop name for better UI display (e.g., CORN_(MAIZE) -> Corn (Maize))
        val cleanCrop = report.crop
            .replace(Regex("_\\(MAIZE\\)"), " (Maize)")
            .replace(Regex("_"), " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }

        // Use the aggregated disease name (e.g., "Common_rust_ (5 Reports)")
        holder.title.text = "$cleanCrop - ${report.disease}"
        holder.details.text = "Severity: ${report.severity} | Last Reported: $dateString"

        // --- 3. Set Severity Color ---
        val colorResId = when (report.severity.uppercase(Locale.ROOT)) {
            "HIGH" -> R.color.severity_high
            "MEDIUM" -> R.color.severity_medium
            "LOW" -> R.color.severity_low
            else -> R.color.severity_none
        }

        val color = ContextCompat.getColor(context, colorResId)
        holder.indicator.setBackgroundColor(color) // Apply color to the indicator bar
    }

    override fun getItemCount() = reports.size

    // Function to update data list
    fun updateReports(newReports: List<RegionalReport>) {
        reports = newReports
        notifyDataSetChanged()
    }
}