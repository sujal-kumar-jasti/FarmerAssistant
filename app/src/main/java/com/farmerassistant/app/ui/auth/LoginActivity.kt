package com.farmerassistant.app.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.farmerassistant.app.R
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.SignInButton
import com.farmerassistant.app.ui.home.HomeActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.farmerassistant.app.utils.LanguageHelper // NEW

// CRITICAL FIX: Web Client ID hardcoded to resolve Gradle resource error
private const val WEB_CLIENT_ID = "431935317983-oiilv1jcihnaha0r9gptkn3darofnhis.apps.googleusercontent.com"
private const val RC_SIGN_IN = 9001

class LoginActivity : AppCompatActivity() {

    private val auth by lazy { Firebase.auth }
    private lateinit var googleSignInClient: GoogleSignInClient

    // Flag to hold app initialization until we check auth status
    private var keepSplashScreen = true

    // NEW: Attach Base Context for Localization
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageHelper.onAttach(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Install Splash Screen and hold the app start
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        super.onCreate(savedInstanceState)

        // 2. Perform the authentication check immediately
        if (checkImmediateAuthStatus()) {
            return // Skip setContentView and UI setup if redirecting
        }

        // 3. If not signed in, proceed to show the login UI
        setContentView(R.layout.activity_login)
        setupGoogleSignIn()

        // UI SETUP: Using safe calls (?.) to prevent NullPointerExceptions
        findViewById<Button>(R.id.btnLogin)?.setOnClickListener { emailPasswordLogin() }
        findViewById<TextView>(R.id.tvRegisterLink)?.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // FIX: Casted to Button to match the MaterialButton used in the new XML layout
        findViewById<Button>(R.id.btnGoogleSignIn)?.setOnClickListener {
            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
        }
    }

    // --- AUTH CHECKER THAT PREVENTS FLICKER ---
    private fun checkImmediateAuthStatus(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        // 1. NEW: Check if language is selected before proceeding
        if (prefs.getString("Locale.Helper.Selected.Language", null) == null) {
            keepSplashScreen = false // Stop splash screen hold
            startActivity(Intent(this, LanguageSelectActivity::class.java))
            finish()
            return true
        }

        // 2. Existing auth check logic
        if (auth.currentUser != null) {
            // User is signed in, check profile status and redirect immediately
            keepSplashScreen = true // Keep splash screen visible until Firestore check completes
            checkUserProfileStatus()
            return true
        } else {
            // No user signed in, stop holding the splash screen and show the login UI
            keepSplashScreen = false
            return false
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun emailPasswordLogin() {
        // NULL SAFETY FIX: Use safe access to EditText fields to prevent crash if views are null
        val email = findViewById<EditText>(R.id.etEmail)?.text?.toString().orEmpty()
        val password = findViewById<EditText>(R.id.etPassword)?.text?.toString().orEmpty()

        if (email.isEmpty() || password.length < 6) {
            Toast.makeText(baseContext, R.string.error_login_credentials_invalid, Toast.LENGTH_LONG).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                checkUserProfileStatus()
            }
            .addOnFailureListener { Toast.makeText(baseContext, "Login failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    // --- Google Sign-In Result Handlers ---

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                account.idToken?.let { token ->
                    firebaseAuthWithGoogle(token)
                }
            } catch (e: ApiException) {
                Log.w("LoginActivity", "Google sign in failed: status=${e.statusCode}", e)
                Toast.makeText(this, "Google Sign In Failed. Error Code: ${e.statusCode}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                checkUserProfileStatus()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Firebase Sign-In Failed: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("LoginActivity", "Firebase Auth Failure: ${e.message}", e)
            }
    }

    // --- CRITICAL PROFILE CHECK FUNCTION (Modified to stop splash screen hold) ---
    private fun checkUserProfileStatus() {
        val user = auth.currentUser
        if (user == null) {
            // Should not happen if called correctly, but good fail-safe
            keepSplashScreen = false
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }

        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                // Check if document exists AND contains the key farm data
                if (document.exists() && document.contains("lat") && document.contains("lng")) {
                    // Profile is complete. Proceed to app.
                    startActivity(Intent(this, HomeActivity::class.java))
                } else {
                    // Profile is missing key data. Force registration details input.
                    Toast.makeText(this, R.string.prompt_complete_profile, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, RegisterActivity::class.java))
                }
                keepSplashScreen = false // Stop holding splash screen just before finishing
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error checking profile status. Please try again.", Toast.LENGTH_LONG).show()
                Log.e("LoginActivity", "Profile status check failed: ${e.message}", e)
                keepSplashScreen = false // Stop holding splash screen on error
            }
    }
}