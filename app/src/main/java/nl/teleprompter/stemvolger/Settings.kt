package nl.teleprompter.stemvolger

import android.content.Context
import androidx.core.content.edit

/** Persisted user preferences and the current script. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)

    var script: String
        get() = prefs.getString("script", null) ?: ""
        set(v) = prefs.edit { putString("script", v) }

    var textSizeSp: Int
        get() = prefs.getInt("textSize", 40)
        set(v) = prefs.edit { putInt("textSize", v) }

    /** Auto-scroll speed in pixels per second at the reference density. */
    var scrollSpeed: Int
        get() = prefs.getInt("scrollSpeed", 60)
        set(v) = prefs.edit { putInt("scrollSpeed", v) }

    var mirror: Boolean
        get() = prefs.getBoolean("mirror", false)
        set(v) = prefs.edit { putBoolean("mirror", v) }

    var voiceMode: Boolean
        get() = prefs.getBoolean("voiceMode", true)
        set(v) = prefs.edit { putBoolean("voiceMode", v) }

    var language: String
        get() = prefs.getString("language", null) ?: defaultLanguage()
        set(v) = prefs.edit { putString("language", v) }

    private fun defaultLanguage(): String {
        val tag = java.util.Locale.getDefault().toLanguageTag()
        return LANGUAGES.firstOrNull { it.first == tag }?.first
            ?: LANGUAGES.firstOrNull { it.first.substringBefore('-') == tag.substringBefore('-') }?.first
            ?: "nl-NL"
    }

    companion object {
        val LANGUAGES = listOf(
            "nl-NL" to "Nederlands (Nederland)",
            "nl-BE" to "Nederlands (België)",
            "en-US" to "English (US)",
            "en-GB" to "English (UK)",
            "de-DE" to "Deutsch",
            "fr-FR" to "Français",
            "es-ES" to "Español",
            "it-IT" to "Italiano",
            "pt-PT" to "Português",
        )
    }
}
