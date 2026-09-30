package dev.hotword.android.settings

import android.content.Context

/** Preferences are snapshot when listening starts; changes require service restart. */
object Preferences {
    private const val FILE = "hotword_prefs"
    private const val KEY_PHRASE = "phrase"
    private const val KEY_LANGUAGE = "language"
    const val DEFAULT_PHRASE = "привет помощник"

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

enum class ModelLanguage(val code: String, val sha256: String, val downloadSources: List<String>) {
    RUSSIAN("ru", "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4", listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        "https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-ru-0.22.zip",
        "https://huggingface.co/rhasspy/vosk-models/resolve/main/ru/vosk-model-small-ru-0.22.zip"
    )),
    ENGLISH("en", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", listOf(
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        "https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-en-us-0.15.zip",
        "https://huggingface.co/rhasspy/vosk-models/resolve/main/en/vosk-model-small-en-us-0.15.zip"
    ));

    companion object {
        fun fromCode(code: String?): ModelLanguage =
            entries.firstOrNull { it.code == code } ?: RUSSIAN
    }
}
