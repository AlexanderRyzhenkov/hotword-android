package dev.hotword.android.audio

import java.text.Normalizer
import java.util.Locale

/** Avoid substring matches inside words and normalize recognition punctuation. */
class TriggerMatcher(phrase: String) {
    private val normalizedPhrase = normalize(phrase)
    val isValid: Boolean get() = normalizedPhrase.isNotBlank()

    fun matches(transcript: String): Boolean {
        if (!isValid) return false
        val words = normalize(transcript)
        return (" $words ").contains(" $normalizedPhrase ")
    }

    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
