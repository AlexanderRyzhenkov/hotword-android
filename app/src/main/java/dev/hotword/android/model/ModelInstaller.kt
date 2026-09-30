package dev.hotword.android.model

import android.content.Context
import dev.hotword.android.settings.ModelLanguage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** User-initiated download only. Model archives are not included in our APK/repository. */
object ModelInstaller {
    private const val MAX_UNPACKED_BYTES = 250L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L

    fun destination(context: Context, language: ModelLanguage): File =
        File(context.filesDir, "models/${language.code}")

    fun isInstalled(context: Context, language: ModelLanguage): Boolean =
        File(destination(context, language), ".ready").isFile

    /** Call on a worker thread, not the UI thread or while recognition is active. */
    fun download(context: Context, language: ModelLanguage, onProgress: (String) -> Unit) {
        val destination = destination(context, language)
        if (isInstalled(context, language)) return
        val parent = destination.parentFile ?: error("Model directory unavailable")
        check(parent.exists() || parent.mkdirs()) { "Cannot create model directory" }
        val stage = File(parent, "${language.code}.tmp")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create temporary model directory" }
        var connection: HttpURLConnection? = null
        try {
            onProgress("Downloading ${language.code} offline model…")
            connection = (URL(language.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                connect()
            }
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val size = connection.contentLengthLong
            check(size < 0 || size <= MAX_ARCHIVE_BYTES) { "Archive too large" }
            var expanded = 0L
            var entries = 0
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++entries <= 10_000) { "Too many archive entries" }
                    val components = entry.name.replace('\\', '/').split('/')
                        .filter { it.isNotEmpty() }
                    check(components.none { it == "." || it == ".." }) { "Unsafe zip entry" }
                    check(components.isNotEmpty()) { "Invalid zip entry" }
                    // Strip the top-level directory distributed by Vosk to make model root stable.
                    val inner = components.drop(1)
                    if (inner.isNotEmpty()) {
                        val target = File(stage, inner.joinToString("/"))
                        check(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) {
                            "Unsafe extraction path"
                        }
                        if (entry.isDirectory) {
                            check(target.isDirectory || target.mkdirs()) { "Cannot create directory" }
                        } else {
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create directory"
                            }
                            target.outputStream().buffered().use { out ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    expanded += count
                                    check(expanded <= MAX_UNPACKED_BYTES) { "Extracted model too large" }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    if (entries % 10 == 0) onProgress("Extracting model: $entries files")
                }
            }
            check(File(stage, "am").isDirectory && File(stage, "conf").isDirectory) {
                "Invalid Vosk model archive"
            }
            check(File(stage, ".ready").createNewFile()) { "Cannot finalize model" }
            check(!destination.exists()) { "Destination already exists" }
            check(stage.renameTo(destination)) { "Cannot install model" }
            onProgress("Model ready")
        } catch (error: Throwable) {
            stage.deleteRecursively()
            throw error
        } finally {
            connection?.disconnect()
        }
    }
}
