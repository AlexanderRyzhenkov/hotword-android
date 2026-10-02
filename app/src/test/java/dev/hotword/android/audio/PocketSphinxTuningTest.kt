package dev.hotword.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketSphinxTuningTest {
    private fun spec(words: Int, syllables: Int) = RussianPronunciation.DictionarySpec(
        keyphrase = "тест",
        lines = listOf("тест t e1 s t"),
        officialWordCount = words,
        fallbackWordCount = 0,
        wordCount = words,
        syllableCount = syllables
    )

    @Test fun singleThreeSyllableWordIsMuchStricterThanTwoWordPhrase() {
        val single = PocketSphinxWakeWordEngine.keywordThreshold(spec(1, 3))
        val twoWord = PocketSphinxWakeWordEngine.keywordThreshold(spec(2, 5))
        assertEquals(1e-15f, single)
        assertEquals(1e-30f, twoWord)
        assertTrue(single > twoWord)
    }

    @Test fun veryShortSingleWordGetsStrictestProfile() {
        assertEquals(1e-10f, PocketSphinxWakeWordEngine.keywordThreshold(spec(1, 2)))
    }

    @Test fun longThreeWordPhraseCanUsePermissiveProfile() {
        assertEquals(1e-35f, PocketSphinxWakeWordEngine.keywordThreshold(spec(3, 8)))
    }
}
