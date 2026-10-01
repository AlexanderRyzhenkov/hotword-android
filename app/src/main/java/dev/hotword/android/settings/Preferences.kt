package dev.hotword.android.settings

import android.content.Context

/** A user-specified phrase is snapshotted when listening starts. */
object Preferences {
    private const val FILE = "hotword_prefs"
    private const val KEY_PHRASE = "phrase"
    private const val KEY_STARTED = "ever_started"
    private const val KEY_TRIGGER_SOUND = "trigger_sound"
    const val DEFAULT_PHRASE = "привет помощник"

    fun phrase(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PHRASE, DEFAULT_PHRASE) ?: DEFAULT_PHRASE

    // Legacy preferences selecting English are intentionally ignored in Russian-only MVP.
    fun language(@Suppress("UNUSED_PARAMETER") context: Context): ModelLanguage = ModelLanguage.RUSSIAN

    fun everStarted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_STARTED, false)

    fun triggerSoundEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRIGGER_SOUND, true)

    fun setTriggerSoundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_TRIGGER_SOUND, enabled).apply()
    }

    fun markEverStarted(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STARTED, true).apply()
    }

    fun save(context: Context, phrase: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHRASE, phrase.trim()).apply()
    }
}

/** This registry can later be extended with other APK-bundled language models. */
enum class ModelLanguage(val code: String, val bundledAsset: String) {
    RUSSIAN("ru", "models/ru.zip")
}
