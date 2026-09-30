package dev.hotword.android.audio

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Vosk recognition. This implementation is replaceable by a low-power KWS engine. */
class VoskWakeWordEngine(
    private val modelDirectory: File,
    phrase: String
) : WakeWordEngine, RecognitionListener {
    private val matcher = TriggerMatcher(phrase)
    private val fired = AtomicBoolean(false)
    private lateinit var onTrigger: () -> Unit
    private lateinit var errorCallback: (Throwable) -> Unit
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    override fun start(onTrigger: () -> Unit, onError: (Throwable) -> Unit) {
        require(matcher.isValid) { "Empty trigger phrase" }
        this.onTrigger = onTrigger
        this.errorCallback = onError
        try {
            model = Model(modelDirectory.absolutePath)
            // Unrestricted vocabulary allows user-supplied phrases; avoid hardcoding model words.
            recognizer = Recognizer(checkNotNull(model), 16000.0f)
            speech = SpeechService(checkNotNull(recognizer), 16000.0f)
            checkNotNull(speech).startListening(this)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun check(json: String, key: String) {
        if (fired.get()) return
        val recognized = runCatching { JSONObject(json).optString(key) }.getOrDefault("")
        if (matcher.matches(recognized) && fired.compareAndSet(false, true)) onTrigger()
    }

    override fun onPartialResult(hypothesis: String) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String) = check(hypothesis, "text")
    override fun onError(exception: Exception) = errorCallback.invoke(exception)
    override fun onTimeout() = Unit

    override fun close() {
        // speech shutdown releases AudioRecord and its recognizer; model is ours to close.
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }
}
