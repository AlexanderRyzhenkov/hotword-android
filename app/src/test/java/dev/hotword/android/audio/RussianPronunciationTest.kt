package dev.hotword.android.audio

import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianPronunciationTest {
    @Test fun normalizesPunctuationAndHyphensIntoWords() {
        assertEquals("привет помощник", RussianPronunciation.normalizePhrase("  Привет, помощник! "))
        assertEquals("эй компьютер", RussianPronunciation.normalizePhrase("эй-компьютер"))
    }

    @Test fun rejectsPhraseWithoutRussianWords() {
        assertFalse(RussianPronunciation.isSupported("hey assistant"))
        assertFalse(RussianPronunciation.isSupported("привет assistant"))
        assertFalse(RussianPronunciation.isSupported("привет 2"))
        assertFalse(RussianPronunciation.isSupported("!!!"))
        assertTrue(RussianPronunciation.isSupported("слушай меня"))
    }

    @Test fun createsStressAlternativesForUnknownWordsWithoutRetrainingModel() {
        val spec = RussianPronunciation.buildDictionary("привет помощник")
        assertEquals("привет помощник", spec.keyphrase)
        assertTrue(spec.lines.any { it.startsWith("привет ") })
        assertTrue(spec.lines.any { it.startsWith("привет(2) ") })
        assertTrue(spec.lines.any { it.startsWith("помощник ") })
        assertTrue(spec.lines.count { it.startsWith("помощник") } >= 3)
        assertEquals(0, spec.officialWordCount)
        assertEquals(2, spec.fallbackWordCount)
    }

    @Test fun officialLexiconReplacesBroadStressFallback() {
        val lexicon = File.createTempFile("ru-lexicon", ".gz")
        try {
            GZIPOutputStream(lexicon.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("алиса a0 lj i1 s a0")
                writer.appendLine("алиса(2) a0 l i1 s a0")
                writer.appendLine("слушай s l u1 sh a0 j")
            }

            val spec = RussianPronunciation.buildDictionary("слушай алиса", lexicon)
            assertEquals(2, spec.officialWordCount)
            assertEquals(0, spec.fallbackWordCount)
            assertEquals(2, spec.wordCount)
            assertEquals(5, spec.syllableCount)
            assertTrue(spec.lines.contains("слушай s l u1 sh a0 j"))
            assertTrue(spec.lines.contains("алиса a0 lj i1 s a0"))
            assertTrue(spec.lines.contains("алиса(2) a0 l i1 s a0"))
        } finally {
            lexicon.delete()
        }
    }

    @Test fun yoIsAlwaysStressed() {
        val pronunciations = RussianPronunciation.pronunciations("ёжик")
        assertEquals(1, pronunciations.size)
        assertTrue(pronunciations.single().contains("o1"))
    }

    @Test fun palatalizesConsonantBeforeSoftVowel() {
        val pronunciations = RussianPronunciation.pronunciations("бери")
        assertTrue(pronunciations.any { it.startsWith("bj e") })
    }
}
