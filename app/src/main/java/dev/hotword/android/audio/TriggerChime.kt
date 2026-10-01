package dev.hotword.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Original, generated confirmation cue: a short descending two-note "tu-dum".
 *
 * The waveform is synthesized in-app, so there is no third-party audio asset to
 * license, attribute, download, or redistribute.
 */
object TriggerChime {
    private const val SAMPLE_RATE = 24_000
    private const val NOTE_MS = 105
    private const val GAP_MS = 24
    private const val TAIL_MS = 45

    fun playBlocking() {
        val pcm = render()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.72f)
            track.play()
            val durationMs = NOTE_MS * 2 + GAP_MS + TAIL_MS
            Thread.sleep(durationMs.toLong())
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    internal fun render(): ShortArray {
        val noteSamples = SAMPLE_RATE * NOTE_MS / 1000
        val gapSamples = SAMPLE_RATE * GAP_MS / 1000
        val tailSamples = SAMPLE_RATE * TAIL_MS / 1000
        val out = ShortArray(noteSamples * 2 + gapSamples + tailSamples)

        // Bright first note, warmer lower answer: clearly audible but not alarm-like.
        synthNote(out, 0, noteSamples, 987.77, 0.42)
        synthNote(out, noteSamples + gapSamples, noteSamples + tailSamples, 739.99, 0.50)
        return out
    }

    private fun synthNote(
        target: ShortArray,
        offset: Int,
        length: Int,
        frequency: Double,
        gain: Double
    ) {
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (i / (SAMPLE_RATE * 0.008)).coerceIn(0.0, 1.0)
            val release = exp(-5.2 * i / length.toDouble())
            val fundamental = sin(2.0 * PI * frequency * t)
            val harmonic = 0.18 * sin(2.0 * PI * frequency * 2.0 * t)
            val sample = ((fundamental + harmonic) * attack * release * gain)
                .coerceIn(-1.0, 1.0)
            target[offset + i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
