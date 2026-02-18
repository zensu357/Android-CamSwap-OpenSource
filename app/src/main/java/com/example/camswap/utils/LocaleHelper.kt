package com.example.camswap.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import java.util.Locale

object LocaleHelper {
    private const val DISABLED = "DISABLED"
    private const val KEY_LANGUAGE = "key_language"

    fun onAttach(context: Context): Context {
        val lang = getPersistedData(context, Locale.getDefault().language)
        return setLocale(context, lang)
    }

    fun getLanguage(context: Context): String {
        return getPersistedData(context, Locale.getDefault().language)
    }

    fun setLocale(context: Context, language: String?): Context {
        persist(context, language)
        if (language == null || language == "") return context

        val locale = if (language == "zh") Locale.CHINESE else Locale(language)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        
        config.setLocale(locale)
        val localeList = LocaleList(locale)
        LocaleList.setDefault(localeList)
        config.setLocales(localeList)
        
        return context.createConfigurationContext(config)
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(KEY_LANGUAGE, "") ?: ""
    }

    private fun persist(context: Context, language: String?) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString(KEY_LANGUAGE, language).apply()
    }
}
