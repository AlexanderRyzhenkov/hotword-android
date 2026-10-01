package dev.hotword.android.audio

import java.util.Locale

/**
 * Small Russian grapheme-to-phoneme helper for the CMUSphinx Russian model.
 *
 * The acoustic model distinguishes stressed/unstressed vowels, while the UI
 * deliberately does not require stress marks. To preserve arbitrary phrases,
 * each word gets alternate dictionary pronunciations with every possible vowel
 * stress position (ё stays stressed). PocketSphinx then chooses acoustically.
 */
object RussianPronunciation {
    data class DictionarySpec(val keyphrase: String, val lines: List<String>)

    private val vowelBase = mapOf(
        'а' to "a", 'я' to "a",
        'у' to "u", 'ю' to "u",
        'о' to "o", 'ё' to "o",
        'э' to "e", 'е' to "e",
        'и' to "i", 'ы' to "y"
    )
    private val softHard = mapOf(
        'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'з' to "z",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'ф' to "f", 'х' to "h"
    )
    private val otherConsonants = mapOf(
        'ж' to "zh", 'ц' to "c", 'ч' to "ch", 'ш' to "sh",
        'щ' to "sch", 'й' to "j"
    )
    private val softLetters = setOf('я', 'ё', 'ю', 'и', 'ь', 'е')
    private val syllableStarts = setOf(
        '#', 'ъ', 'ь', '-', 'а', 'я', 'о', 'ё', 'у', 'ю', 'э', 'е', 'и', 'ы'
    )
    private val jotVowels = setOf('я', 'ю', 'е', 'ё')
    private val validWord = Regex("^[а-яёъь]+$")

    fun normalizePhrase(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace(Regex("[^а-яёъь]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun isSupported(raw: String): Boolean {
        // Punctuation is fine, but silently dropping Latin letters/digits would
        // make the configured wake phrase differ from what the user entered.
        if (raw.any { it.isDigit() }) return false
        if (raw.any { ch ->
                ch.isLetter() && ch.lowercaseChar() !in 'а'..'я' && ch.lowercaseChar() != 'ё'
            }) return false
        val normalized = normalizePhrase(raw)
        if (normalized.isBlank()) return false
        return normalized.split(' ').all { validWord.matches(it) }
    }

    fun buildDictionary(raw: String): DictionarySpec {
        val keyphrase = normalizePhrase(raw)
        require(keyphrase.isNotBlank()) { "Empty Russian keyphrase" }
        val words = keyphrase.split(' ').distinct()
        val lines = buildList {
            for (word in words) {
                val variants = pronunciations(word)
                require(variants.isNotEmpty()) { "Cannot pronounce word: $word" }
                variants.forEachIndexed { index, phones ->
                    val token = if (index == 0) word else "$word(${index + 1})"
                    add("$token $phones")
                }
            }
        }
        return DictionarySpec(keyphrase, lines)
    }

    internal fun pronunciations(word: String): List<String> {
        require(validWord.matches(word)) { "Unsupported Russian word: $word" }
        val yo = word.indices.filter { word[it] == 'ё' }
        val stressPositions = if (yo.isNotEmpty()) {
            yo
        } else {
            word.indices.filter { vowelBase.containsKey(word[it]) }
        }
        val candidates = if (stressPositions.isEmpty()) listOf(-1) else stressPositions
        return candidates.map { stress -> transcribe(word, stress) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun transcribe(word: String, stressAt: Int): String {
        val phones = mutableListOf<String>()
        for (index in word.indices) {
            val ch = word[index]
            val previous = if (index == 0) '#' else word[index - 1]
            val next = if (index == word.lastIndex) '#' else word[index + 1]

            val soft = softHard[ch]
            if (soft != null) {
                phones += if (next in softLetters) soft + "j" else soft
                continue
            }
            val other = otherConsonants[ch]
            if (other != null) {
                phones += other
                continue
            }
            val vowel = vowelBase[ch]
            if (vowel != null) {
                if (previous in syllableStarts && ch in jotVowels) phones += "j"
                val stressed = index == stressAt || ch == 'ё'
                phones += vowel + if (stressed) "1" else "0"
            }
            // ь and ъ only modify neighboring phones.
        }
        return phones.joinToString(" ")
    }
}
