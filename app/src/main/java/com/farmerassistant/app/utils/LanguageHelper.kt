package com.farmerassistant.app.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.Locale

object LanguageHelper {

    private const val SELECTED_LANGUAGE = "Locale.Helper.Selected.Language"
    private const val DEFAULT_LANGUAGE = "en" // Default to English

    /**
     * Persists the selected language code and returns a ContextWrapper with the new locale.
     */
    fun setLocale(context: Context, language: String): ContextWrapper {
        persist(context, language)
        return updateResources(context, language)
    }

    /**
     * Retrieves the saved language preference and applies it to the context.
     */
    fun onAttach(context: Context): ContextWrapper {
        val language = getPersistedData(context)
        return updateResources(context, language)
    }

    fun getPersistedData(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(SELECTED_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    private fun persist(context: Context, language: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(SELECTED_LANGUAGE, language).apply()
    }

    private fun updateResources(context: Context, language: String): ContextWrapper {
        var localContext = context
        val locale = Locale(language)
        Locale.setDefault(locale)

        val resources = context.resources
        val config = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            localContext = context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
        return ContextWrapper(localContext)
    }
}