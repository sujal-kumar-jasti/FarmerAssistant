package com.farmerassistant.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.farmerassistant.app.R
import com.farmerassistant.app.ui.home.fragments.*
import com.farmerassistant.app.utils.LanguageHelper
import com.farmerassistant.app.workers.ClimateWorker
import com.farmerassistant.app.workers.RegionalAlertWorker
import com.farmerassistant.app.workers.SoilAnalysisWorker
import com.farmerassistant.app.workers.YieldPredictionWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.concurrent.TimeUnit
import com.farmerassistant.app.workers.MarketAlertWorker


class HomeActivity : AppCompatActivity() {

    // 🔥 Initialize the BottomNavigationView property for cleaner access
    private lateinit var bottomNav: BottomNavigationView

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted. Workers can now post notifications.
            } else {
                Log.w("HomeActivity", "Notification permission denied.")
            }
        }

    companion object {
        const val NOTIFICATION_TARGET_FRAGMENT_KEY = "target_fragment"
        const val FRAGMENT_CLIMATE = "climate"
        const val FRAGMENT_YIELD = "yield"
        const val FRAGMENT_DISEASE = "disease"
        const val FRAGMENT_ALERTS = "alerts"

        const val FRAGMENT_MARKET = "market"
    }


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        requestNotificationPermission()

        // Schedule workers (using self-sufficient requests, no inputData needed)
        scheduleDailySoilAnalysis()
        schedulePeriodicClimateUpdate()
        scheduleYieldPrediction()
        scheduleRegionalAlertCheck()
        scheduleMarketAlertCheck()

        bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_disease -> DiseaseFragment()
                R.id.nav_climate -> ClimateFragment()
                R.id.nav_yield -> YieldPredictionFragment()
                R.id.nav_chat -> ChatbotFragment()
                R.id.nav_account -> AccountFragment()
                else -> return@setOnItemSelectedListener false
            }
            loadFragment(fragment)
            true
        }

        // 🔥 1. CENTRALIZED LOGIC CALL: Attempt to redirect based on the launching intent
        val redirected = handleIntentRedirection(intent)

        // 2. SET DEFAULT: If not redirected by notification and not restoring state, set default.
        // This replaces the old, confusing logic at the end of the original onCreate.
        if (savedInstanceState == null && !redirected) {
            bottomNav.selectedItemId = R.id.nav_disease
        }
    }

    /**
     * 🔥 CRITICAL FIX: Handles new intents (like notification clicks) when the activity is already running.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        handleIntentRedirection(intent)
    }

    /**
     * Centralized logic to read the intent extra and trigger navigation.
     * Ensures only navigation occurs on notification click.
     * @return true if navigation occurred, false otherwise.
     */

    private fun scheduleMarketAlertCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequest.Builder(
            MarketAlertWorker::class.java,
            12, // Repeat interval: 12 hours (e.g., after morning/evening data updates)
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MarketAlertWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    // ... (rest of HomeActivity) ...

    // 🔥 CRITICAL FIX: Update handleIntentRedirection to handle market redirect

    private fun handleIntentRedirection(intent: Intent?): Boolean {
        if (!::bottomNav.isInitialized) return false

        val targetFragment = intent?.getStringExtra(NOTIFICATION_TARGET_FRAGMENT_KEY)

        if (targetFragment != null) {
            val targetId = when (targetFragment) {
                FRAGMENT_CLIMATE -> R.id.nav_climate
                FRAGMENT_YIELD -> R.id.nav_yield
                FRAGMENT_DISEASE -> R.id.nav_disease
                FRAGMENT_MARKET -> R.id.nav_yield
                else -> null
            }

            if (targetId != null) {
                Log.i("HomeActivity", "Redirected to $targetFragment fragment from notification.")
                // Action 1: Navigate
                bottomNav.selectedItemId = targetId

                // CRUCIAL: Action 2: Clear the extra data immediately after use
                intent?.removeExtra(NOTIFICATION_TARGET_FRAGMENT_KEY)
                return true
            }
        }
        return false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // --- Worker Scheduling Methods (Using self-sufficient periodic requests) ---

    private fun scheduleDailySoilAnalysis() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequest.Builder(
            SoilAnalysisWorker::class.java,
            24,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            // 🔥 No setInputData call needed here
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SoilAnalysisWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }


    private fun schedulePeriodicClimateUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequest.Builder(
            ClimateWorker::class.java,
            6,
            TimeUnit.HOURS,
            1,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            // 🔥 No setInputData call needed here
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ClimateWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun scheduleYieldPrediction() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequest.Builder(
            YieldPredictionWorker::class.java,
            24,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            // 🔥 No setInputData call needed here
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            YieldPredictionWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }


    private fun scheduleRegionalAlertCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = PeriodicWorkRequest.Builder(
            RegionalAlertWorker::class.java,
            12, // Repeat interval: 12 hours
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            // 🔥 No setInputData call needed here
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            RegionalAlertWorker.WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }


    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}