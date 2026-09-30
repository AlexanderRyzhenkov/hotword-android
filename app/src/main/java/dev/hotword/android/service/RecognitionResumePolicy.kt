package dev.hotword.android.service

/**
 * Hands off the microphone to the assistant without an arbitrary 20-second blind spot.
 * Until the assistant's recording becomes visible, allow a short startup window.
 * Once it is recording, resume shortly after other recording activity stops.
 * A deadline handles platforms that report microphone status inconsistently.
 */
internal class RecognitionResumePolicy(
    private val startedAtMs: Long,
    private val startupWindowMs: Long = 3_000L,
    private val quietWindowMs: Long = 800L,
    private val maxWaitMs: Long = 30_000L
) {
    private var otherRecorderObserved = false
    private var quietSinceMs: Long? = null

    fun shouldResume(nowMs: Long, otherRecordingActive: Boolean): Boolean {
        if (nowMs - startedAtMs >= maxWaitMs) return true
        if (otherRecordingActive) {
            otherRecorderObserved = true
            quietSinceMs = null
            return false
        }
        if (!otherRecorderObserved) return nowMs - startedAtMs >= startupWindowMs
        val quietSince = quietSinceMs ?: nowMs.also { quietSinceMs = it }
        return nowMs - quietSince >= quietWindowMs
    }
}
