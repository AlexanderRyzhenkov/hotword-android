package dev.hotword.android.audio

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep the costly Vosk model in memory throughout the service lifetime.
 * During the assistant session, release AudioRecord and the recognizer only;
 * reopening a recording must not reload the full native model from disk.
 *
 * All lifecycle calls are serialized by WakeWordService's worker executor.
 */
class VoskWakeWordEngine(
    private val modelDirectory: File,
    phrase: String
) : WakeWordEngine {
    @Volatile private var matcher = TriggerMatcher(phrase)
    private val fired = AtomicBoolean(false)
    private lateinit var onTrigger: () -> Unit
    private lateinit var errorCallback: (Throwable) -> Unit
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    // Volatile: Vosk invokes callbacks on a separate capture thread.
    @Volatile private var generation = 0
    @Volatile private var listening = false

    override fun start(onTrigger: () -> Unit, onError: (Throwable) -> Unit) {
        require(matcher.isValid) { "Empty trigger phrase" }
        this.onTrigger = onTrigger
        this.errorCallback = onError
        try {
            model = Model(modelDirectory.absolutePath)
            resume()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun updatePhrase(phrase: String) {
        val next = TriggerMatcher(phrase)
        require(next.isValid) { "Empty trigger phrase" }
        matcher = next
    }

    override fun resume() {
        check(model != null) { "Model has not been initialized" }
        if (listening) return
        fired.set(false)
        val token = ++generation
        try {
            recognizer = Recognizer(checkNotNull(model), 16_000.0f)
            speech = SpeechService(checkNotNull(recognizer), 16_000.0f)
            listening = true
            checkNotNull(speech).startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String) =
                    checkResult(hypothesis, "partial", token)
                override fun onResult(hypothesis: String) =
                    checkResult(hypothesis, "text", token)
                override fun onFinalResult(hypothesis: String) =
                    checkResult(hypothesis, "text", token)
                override fun onError(exception: Exception) {
                    if (token == generation && listening) errorCallback(exception)
                }
                override fun onTimeout() = Unit
            })
        } catch (error: Throwable) {
            pause()
            throw error
        }
    }

    private fun checkResult(json: String, key: String, token: Int) {
        if (token != generation || !listening || fired.get()) return
        val transcript = runCatching { JSONObject(json).optString(key) }.getOrDefault("")
        if (matcher.matches(transcript) && fired.compareAndSet(false, true)) {
            onTrigger()
        }
    }

    override fun pause() {
        listening = false
        generation++ // Discard late results from the old AudioRecord.
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    override fun close() {
        pause()
        runCatching { model?.close() }
        model = null
    }
}
