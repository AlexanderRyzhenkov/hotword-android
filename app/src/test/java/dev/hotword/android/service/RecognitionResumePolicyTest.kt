package dev.hotword.android.service

import org.junit.Assert.*
import org.junit.Test

class RecognitionResumePolicyTest {
    @Test fun resumesQuicklyIfAssistantNeverUsesMicrophone() {
        val policy = RecognitionResumePolicy(100L)
        assertFalse(policy.shouldResume(1_500L, false))
        assertFalse(policy.shouldResume(3_099L, false))
        assertTrue(policy.shouldResume(3_100L, false))
    }

    @Test fun waitsUntilAssistantReleasesMicrophone() {
        val policy = RecognitionResumePolicy(0L)
        assertFalse(policy.shouldResume(2_900L, true))
        assertFalse(policy.shouldResume(3_500L, true))
        assertFalse(policy.shouldResume(4_000L, false))
        assertFalse(policy.shouldResume(4_799L, false))
        assertTrue(policy.shouldResume(4_800L, false))
    }

    @Test fun briefInterruptionDoesNotResumeTooEarly() {
        val policy = RecognitionResumePolicy(0L)
        assertFalse(policy.shouldResume(1_000L, true))
        assertFalse(policy.shouldResume(2_000L, false))
        assertFalse(policy.shouldResume(2_300L, true))
        assertFalse(policy.shouldResume(3_000L, false))
        assertTrue(policy.shouldResume(3_800L, false))
    }

    @Test fun deadlineHandlesUnreliableRecordingInformation() {
        val policy = RecognitionResumePolicy(0L)
        assertFalse(policy.shouldResume(29_000L, true))
        assertTrue(policy.shouldResume(30_000L, true))
    }
}
