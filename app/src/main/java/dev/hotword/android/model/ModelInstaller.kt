package dev.hotword.android.model

import android.content.Context
import dev.hotword.android.settings.ModelLanguage
import java.io.File
import java.io.IOException

/** Copies the APK-bundled PocketSphinx acoustic model into private files. */
object ModelInstaller {
    private const val BUFFER_SIZE = 32 * 1024
    private const val MAX_UNPACKED_BYTES = 192L * 1024L * 1024L

    private val requiredFiles = listOf(
        "feat.params",
        "feature_transform",
        "mdef",
        "means",
        "mixture_weights",
        "noisedict",
        "transition_matrices",
        "variances",
        "ru.lexicon.gz"
    )

    fun destination(context: Context, language: ModelLanguage): File =
        File(context.filesDir, "pocketsphinx/" + language.code)

    fun workDirectory(context: Context): File =
        File(context.filesDir, "pocketsphinx/runtime")

    fun isInstalled(context: Context, language: ModelLanguage): Boolean {
        val dest = destination(context, language)
        return File(dest, ".ready").isFile &&
            requiredFiles.all { File(dest, it).isFile && File(dest, it).length() > 0L }
    }

    /** Runs on a worker thread; synchronization protects activity recreation. */
    @Synchronized
    fun ensureInstalled(context: Context, language: ModelLanguage, progress: (Int) -> Unit) {
        cleanupLegacyVosk(context)
        if (isInstalled(context, language)) return
        val target = destination(context, language)
        val parent = target.parentFile ?: throw IOException("Model directory unavailable")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create model directory")

        val stage = File(parent, language.code + ".staging")
        if (stage.exists() && !stage.deleteRecursively()) throw IOException("Cannot clear staging directory")
        if (!stage.mkdirs()) throw IOException("Cannot create staging directory")

        try {
            var copied = 0L
            requiredFiles.forEachIndexed { index, name ->
                val output = File(stage, name)
                context.assets.open(language.bundledAsset + "/" + name).buffered().use { input ->
                    output.outputStream().buffered().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_UNPACKED_BYTES) {
                                throw IOException("Bundled acoustic model is unexpectedly large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
                progress(index + 1)
            }

            if (!File(stage, ".ready").createNewFile()) {
                throw IOException("Cannot mark model ready")
            }
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException("Cannot replace incomplete model")
            }
            if (!stage.renameTo(target)) throw IOException("Cannot finalize model")
            progress(-1)
        } catch (error: Exception) {
            throw IOException("Bundled PocketSphinx model preparation failed: " + error.message, error)
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Previous releases unpacked Vosk under files/models/ru; it is unused now. */
    private fun cleanupLegacyVosk(context: Context) {
        val legacy = File(context.filesDir, "models/ru")
        if (legacy.exists()) runCatching { legacy.deleteRecursively() }
        val parent = legacy.parentFile
        if (parent?.isDirectory == true && parent.listFiles()?.isEmpty() == true) {
            runCatching { parent.delete() }
        }
    }
}
