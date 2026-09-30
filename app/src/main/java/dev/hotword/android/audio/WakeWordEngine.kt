package dev.hotword.android.audio

/** Replaceable recognizer with a reusable, pausable model for quick handoff. */
interface WakeWordEngine {
    fun start(onTrigger: () -> Unit, onError: (Throwable) -> Unit)
    fun updatePhrase(phrase: String)
    fun pause()
    fun resume()
    fun close()
}
