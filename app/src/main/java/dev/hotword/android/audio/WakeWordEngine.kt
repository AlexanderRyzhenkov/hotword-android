package dev.hotword.android.audio

/** Swappable engine: current MVP uses streaming ASR, not a dedicated KWS model. */
interface WakeWordEngine {
    fun start(onTrigger: () -> Unit, onError: (Throwable) -> Unit)
    fun close()
}
