package dev.hotword.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerChimeTest {
    @Test fun rendersShortNonSilentCue() {
        val pcm = TriggerChime.render()
        assertEquals(6_696, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 })
        assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } <= Short.MAX_VALUE.toInt())
    }
}
