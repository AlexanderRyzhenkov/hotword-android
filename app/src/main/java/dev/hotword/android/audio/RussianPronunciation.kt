package dev.hotword.android.audio

import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Russian pronunciation preparation for the CMUSphinx acoustic model.
 *
 * Prefer the official cmusphinx-ru-5.2 ru.dic pronunciation whenever the word
 * is present. Only unknown words fall back to the lightweight heuristic G2P.
 * This avoids broad, incorrect stress alternatives for common wake words while
 * preserving the ability to enter arbitrary Russian phrases without retraining.
 */
object RussianPronunciation {
    data class DictionarySpec(
        val keyphrase: String,
        val lines: List<String>,
        val officialWordCount: Int,
        val fallbackWordCount: Int,
        val wordCount: Int,
        val syllableCount: Int
    )

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
        if (raw.any { it.isDigit() }) return false
        if (raw.any { ch ->
                ch.isLetter() && ch.lowercaseChar() !in 'а'..'я' && ch.lowercaseChar() != 'ё'
            }) return false
        val normalized = normalizePhrase(raw)
        if (normalized.isBlank()) return false
        return normalized.split(' ').all { validWord.matches(it) }
    }

    fun buildDictionary(raw: String, officialLexicon: File? = null): DictionarySpec {
        val keyphrase = normalizePhrase(raw)
        require(keyphrase.isNotBlank()) { "Empty Russian keyphrase" }
        val phraseWords = keyphrase.split(' ')
        val words = phraseWords.distinct()
        val official = if (officialLexicon?.isFile == true) {
            lookupOfficial(officialLexicon, words.toSet())
        } else {
            emptyMap()
        }

        var officialWordCount = 0
        var fallbackWordCount = 0
        val lines = buildList {
            for (word in words) {
                val fromOfficial = official[word].orEmpty().distinct()
                val variants = if (fromOfficial.isNotEmpty()) {
                    officialWordCount++
                    fromOfficial
                } else {
                    fallbackWordCount++
                    pronunciations(word)
                }
                require(variants.isNotEmpty()) { "Cannot pronounce word: $word" }
                variants.forEachIndexed { index, phones ->
                    val token = if (index == 0) word else "$word(${index + 1})"
                    add("$token $phones")
                }
            }
        }

        return DictionarySpec(
            keyphrase = keyphrase,
            lines = lines,
            officialWordCount = officialWordCount,
            fallbackWordCount = fallbackWordCount,
            wordCount = phraseWords.size,
            syllableCount = keyphrase.count { vowelBase.containsKey(it) }
        )
    }

    /** Stream the compressed 545k-word lexicon only when the phrase changes. */
    internal fun lookupOfficial(file: File, words: Set<String>): Map<String, List<String>> {
        if (words.isEmpty()) return emptyMap()
        val found = linkedMapOf<String, MutableList<String>>()
        GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { sequence ->
            sequence.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                val split = line.indexOfFirst { it.isWhitespace() }
                if (split <= 0) return@forEach
                val token = line.substring(0, split)
                val base = token.substringBefore('(').lowercase(Locale.ROOT)
                if (base !in words) return@forEach
                val phones = line.substring(split).trim()
                if (phones.isNotEmpty()) found.getOrPut(base) { mutableListOf() }.add(phones)
            }
        }
        return found
    }

    /** Fallback only for words absent from the official dictionary. */
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
        }
        return phones.joinToString(" ")
    }
}
