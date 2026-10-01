package dev.hotword.android.audio

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
        assertFalse(RussianPronunciation.isSupported("!!!"))
        assertTrue(RussianPronunciation.isSupported("слушай меня"))
    }

    @Test fun createsStressAlternativesWithoutRetrainingModel() {
        val spec = RussianPronunciation.buildDictionary("привет помощник")
        assertEquals("привет помощник", spec.keyphrase)
        assertTrue(spec.lines.any { it.startsWith("привет ") })
        assertTrue(spec.lines.any { it.startsWith("привет(2) ") })
        assertTrue(spec.lines.any { it.startsWith("помощник ") })
        assertTrue(spec.lines.count { it.startsWith("помощник") } >= 3)
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
