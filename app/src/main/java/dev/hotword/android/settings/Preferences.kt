package dev.hotword.android.settings

import android.content.Context

/** Preferences are snapshot when listening starts; changes require service restart. */
object Preferences {
    private const val FILE = "hotword_prefs"
    private const val KEY_PHRASE = "phrase"
    private const val KEY_LANGUAGE = "language"
    const val DEFAULT_PHRASE = "алиса"

    fun phrase(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PHRASE, DEFAULT_PHRASE) ?: DEFAULT_PHRASE

    fun language(context: Context): ModelLanguage =
        ModelLanguage.fromCode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, ModelLanguage.RUSSIAN.code))

    fun save(context: Context, phrase: String, language: ModelLanguage) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHRASE, phrase.trim())
            .putString(KEY_LANGUAGE, language.code)
            .apply()
    }
}

enum class ModelLanguage(val code: String, val downloadUrl: String) {
    RUSSIAN("ru", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
    ENGLISH("en", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip");

    companion object {
        fun fromCode(code: String?): ModelLanguage =
            entries.firstOrNull { it.code == code } ?: RUSSIAN
    }
}
