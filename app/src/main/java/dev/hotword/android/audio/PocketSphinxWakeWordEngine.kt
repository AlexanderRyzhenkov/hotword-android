package dev.hotword.android.audio

import android.util.Log
import edu.cmu.pocketsphinx.Hypothesis
import edu.cmu.pocketsphinx.RecognitionListener
import edu.cmu.pocketsphinx.SpeechRecognizer
import edu.cmu.pocketsphinx.SpeechRecognizerSetup
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low-compute single-keyphrase recognizer backed by PocketSphinx.
 *
 * Unlike Vosk ASR, this decoder does not transcribe arbitrary surrounding
 * speech. It runs PocketSphinx keyword search for one user phrase only.
 */
class PocketSphinxWakeWordEngine(
    private val acousticModel: File,
    private val workingDirectory: File,
    phrase: String
) : WakeWordEngine {
    private var phrase = phrase
    private var keyphrase = ""
    private var recognizer: SpeechRecognizer? = null
    private lateinit var onTrigger: () -> Unit
    private lateinit var onErrorCallback: (Throwable) -> Unit
    private val fired = AtomicBoolean(false)
    @Volatile private var listening = false

    private val listener = object : RecognitionListener {
        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResult(hypothesis: Hypothesis?) {
            val text = hypothesis?.hypstr ?: return
            if (listening && text == keyphrase && fired.compareAndSet(false, true)) {
                onTrigger()
            }
        }

        override fun onResult(hypothesis: Hypothesis?) = Unit

        override fun onError(exception: Exception) {
            if (listening) onErrorCallback(exception)
        }

        override fun onTimeout() = Unit
    }

    override fun start(onTrigger: () -> Unit, onError: (Throwable) -> Unit) {
        this.onTrigger = onTrigger
        this.onErrorCallback = onError
        ensureRecognizer()
        resume()
    }

    override fun updatePhrase(phrase: String) {
        val normalized = RussianPronunciation.normalizePhrase(phrase)
        if (normalized == RussianPronunciation.normalizePhrase(this.phrase)) return
        this.phrase = phrase
        destroyRecognizer()
    }

    override fun pause() {
        listening = false
        fired.set(false)
        runCatching { recognizer?.cancel() }
    }

    override fun resume() {
        ensureRecognizer()
        if (listening) return
        fired.set(false)
        listening = checkNotNull(recognizer).startListening(SEARCH_NAME)
        check(listening) { "PocketSphinx listener was already active" }
    }

    override fun close() {
        pause()
        destroyRecognizer()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        require(acousticModel.isDirectory) { "PocketSphinx acoustic model missing" }
        if (!workingDirectory.isDirectory && !workingDirectory.mkdirs()) {
            error("Cannot create PocketSphinx working directory")
        }

        val dictionary = RussianPronunciation.buildDictionary(
            phrase,
            File(acousticModel, "ru.lexicon")
        )
        keyphrase = dictionary.keyphrase
        val dictionaryFile = File(workingDirectory, "keyphrase.dict")
        dictionaryFile.writeText(dictionary.lines.joinToString("\n", postfix = "\n"))

        val threshold = keywordThreshold(dictionary)
        Log.i(
            TAG,
            "KWS phrase words=${dictionary.wordCount}, syllables=${dictionary.syllableCount}, " +
                "official=${dictionary.officialWordCount}, fallback=${dictionary.fallbackWordCount}, " +
                "threshold=$threshold"
        )

        val setup = SpeechRecognizerSetup.defaultSetup()
            .setAcousticModel(acousticModel)
            .setDictionary(dictionaryFile)
            .setSampleRate(16_000)
            .setKeywordThreshold(threshold)
            .setString("-fdict", File(acousticModel, "noisedict").absolutePath)
            .setString("-lda", File(acousticModel, "feature_transform").absolutePath)
            .setBoolean("-backtrace", false)

        val next = setup.recognizer
        next.addListener(listener)
        next.addKeyphraseSearch(SEARCH_NAME, keyphrase)
        recognizer = next
    }

    private fun destroyRecognizer() {
        listening = false
        fired.set(false)
        recognizer?.let { current ->
            runCatching { current.cancel() }
            runCatching { current.removeListener(listener) }
            runCatching { current.shutdown() }
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "HotwordPocketSphinx"
        private const val SEARCH_NAME = "hotword"

        /**
         * PocketSphinx thresholds are phrase-specific: larger values are stricter.
         * Short one-word wake phrases need much stronger false-positive filtering,
         * while longer phrases can safely use a more permissive threshold.
         */
        internal fun keywordThreshold(spec: RussianPronunciation.DictionarySpec): Float =
            when {
                spec.wordCount == 1 && spec.syllableCount <= 2 -> 1e-10f
                spec.wordCount == 1 && spec.syllableCount == 3 -> 1e-15f
                spec.wordCount == 1 -> 1e-20f
                spec.wordCount == 2 && spec.syllableCount <= 4 -> 1e-25f
                spec.wordCount >= 3 -> 1e-35f
                else -> 1e-30f
            }
    }
}
