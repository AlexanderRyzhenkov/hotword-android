package dev.hotword.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {
    @Test fun matchesMultipleWordPhraseWithPunctuation() {
        assertTrue(TriggerMatcher("слушай, алиса").matches("Пожалуйста, слушай алиса: включи свет"))
    }

    @Test fun normalizationHandlesRussianYo() {
        assertTrue(TriggerMatcher("ёжик").matches("ежик"))
    }

    @Test fun avoidsMatchingFragmentsInsideWords() {
        assertFalse(TriggerMatcher("алиса").matches("алисандра"))
    }

    @Test fun recognizesEnglish() {
        assertTrue(TriggerMatcher("hey assistant").matches("OK, hey assistant, please"))
    }

    @Test fun rejectsEmptyPhrase() {
        assertFalse(TriggerMatcher("! .").isValid)
    }
}
