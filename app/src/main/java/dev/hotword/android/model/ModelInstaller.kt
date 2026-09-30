package dev.hotword.android.model

import android.content.Context
import dev.hotword.android.settings.ModelLanguage
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Extracts the offline APK-bundled model without network access. */
object ModelInstaller {
    private const val MAX_UNPACKED_BYTES = 250L * 1024L * 1024L
    private const val MAX_ENTRIES = 10_000
    private const val BUFFER_SIZE = 32 * 1024

    fun destination(context: Context, language: ModelLanguage): File =
        File(context.filesDir, "models/" + language.code)

    fun isInstalled(context: Context, language: ModelLanguage): Boolean {
        val dest = destination(context, language)
        return File(dest, ".ready").isFile && File(dest, "am").isDirectory &&
            File(dest, "conf").isDirectory
    }

    /** Runs on a worker thread; synchronization protects against activity recreation. */
    @Synchronized
    fun ensureInstalled(context: Context, language: ModelLanguage, progress: (Int) -> Unit) {
        if (isInstalled(context, language)) return
        val target = destination(context, language)
        val parent = target.parentFile ?: throw IOException("Model directory unavailable")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create model directory")
        val stage = File(parent, language.code + ".staging")
        if (stage.exists() && !stage.deleteRecursively()) throw IOException("Cannot clear staging directory")
        if (!stage.mkdirs()) throw IOException("Cannot create staging directory")
        try {
            progress(0)
            var expanded = 0L
            var entries = 0
            var archiveRoot: String? = null
            context.assets.open(language.bundledAsset).buffered().use { asset ->
                ZipInputStream(asset).use { zip ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > MAX_ENTRIES) throw IOException("Too many ZIP entries")
                        val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                        if (parts.isEmpty() || parts.any { it == "." || it == ".." })
                            throw IOException("Unsafe ZIP entry")
                        if (archiveRoot == null) archiveRoot = parts.first()
                        else if (parts.first() != archiveRoot)
                            throw IOException("More than one model root in ZIP")
                        val inside = parts.drop(1)
                        if (inside.isNotEmpty()) {
                            val relative = inside.joinToString("/")
                            if (relative == ".ready") throw IOException("Reserved model file")
                            val file = File(stage, relative)
                            if (!file.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                                throw IOException("ZIP path traversal")
                            if (entry.isDirectory) {
                                if (!file.isDirectory && !file.mkdirs())
                                    throw IOException("Cannot create directory")
                            } else {
                                val folder = file.parentFile ?: throw IOException("Missing parent directory")
                                if (!folder.isDirectory && !folder.mkdirs())
                                    throw IOException("Cannot create directory")
                                file.outputStream().buffered().use { output ->
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        expanded += count
                                        if (expanded > MAX_UNPACKED_BYTES)
                                            throw IOException("Extracted model is too large")
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        if (entries % 25 == 0) progress(entries)
                    }
                }
            }
            if (!File(stage, "am").isDirectory || !File(stage, "conf").isDirectory)
                throw IOException("Bundled model missing Vosk directories")
            if (!File(stage, ".ready").createNewFile()) throw IOException("Cannot mark model ready")
            if (target.exists() && !target.deleteRecursively())
                throw IOException("Cannot replace incomplete model")
            if (!stage.renameTo(target)) throw IOException("Cannot finalize model")
            progress(-1)
        } catch (error: Exception) {
            throw IOException("Bundled model preparation failed: " + error.message, error)
        } finally {
            stage.deleteRecursively()
        }
    }
}
