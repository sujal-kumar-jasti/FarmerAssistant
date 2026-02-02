package com.farmerassistant.app.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.farmerassistant.app.R
import com.farmerassistant.app.ui.home.HomeActivity
import com.farmerassistant.app.utils.LanguageHelper
import com.google.android.material.card.MaterialCardView
import android.util.TypedValue

class LanguageSelectActivity : AppCompatActivity() {

    private val languageMap = mapOf(
        R.string.lang_en to "en",
        R.string.lang_hi to "hi",
        R.string.lang_te to "te",
        R.string.lang_kn to "kn",
        R.string.lang_ta to "ta"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_select)

        val container = findViewById<LinearLayout>(R.id.languageContainer)

        // Dynamically create language selection cards
        languageMap.forEach { (langNameResId, langCode) ->
            val langName = getString(langNameResId)
            val card = createLanguageCard(langName, langCode)
            container.addView(card)
        }
    }

    private fun createLanguageCard(langName: String, langCode: String): MaterialCardView {
        val context = this
        val dp12 = resources.getDimensionPixelSize(R.dimen.card_margin)
        val dp16 = resources.getDimensionPixelSize(R.dimen.card_padding)
        val cardRadiusPx = resources.getDimension(R.dimen.card_radius)

        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp12
            }
            radius = cardRadiusPx
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.md_theme_surfaceVariant))
            setOnClickListener {
                // Apply and save the language
                LanguageHelper.setLocale(context, langCode)

                // Restart app to load resources in the new language
                Toast.makeText(context, resources.getString(R.string.language_change_confirm), Toast.LENGTH_SHORT).show()

                val isFromAccount = intent.getBooleanExtra("from_account", false)
                if (isFromAccount) {
                    // Refresh HomeActivity after selection
                    val refreshIntent = Intent(context, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(refreshIntent)
                } else {
                    // Proceed to Login after initial setup
                    val nextIntent = Intent(context, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(nextIntent)
                }
                finish()
            }
        }

        // Setup TextView inside the card
        val textView = TextView(context).apply {
            text = langName
            @Suppress("DEPRECATION")
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
            setPadding(dp16, dp16, dp16, dp16)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.addView(textView)
        return card
    }

    override fun attachBaseContext(newBase: Context) {
        // IMPORTANT: Do NOT apply LanguageHelper.onAttach here, as it would localize the hardcoded language names.
        super.attachBaseContext(newBase)
    }
}