package dev.hotword.android.model

import android.content.Context
import android.net.Uri
import dev.hotword.android.settings.ModelLanguage
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Model downloads are resumable; all archives are checked against pinned SHA-256 hashes. */
object ModelInstaller {
    private const val MAX_ARCHIVE = 100L * 1024 * 1024
    private const val MAX_EXTRACTED = 250L * 1024 * 1024
    private const val BUFFER_SIZE = 32 * 1024
    private const val ATTEMPTS_PER_SOURCE = 2

    fun destination(context: Context, language: ModelLanguage): File =
        File(context.filesDir, "models/${language.code}")

    fun isInstalled(context: Context, language: ModelLanguage): Boolean =
        File(destination(context, language), ".ready").isFile

    /** Called only from a worker thread. Partial downloads survive failure and app restarts. */
    fun download(context: Context, language: ModelLanguage, onProgress: (String) -> Unit) {
        if (isInstalled(context, language)) return
        val archive = partialFile(context, language)
        if (!checksumMatches(archive, language.sha256)) {
            val failures = mutableListOf<String>()
            downloadLoop@ for ((index, source) in language.downloadSources.withIndex()) {
                for (attempt in 1..ATTEMPTS_PER_SOURCE) {
                    try {
                        onProgress("Source ${index + 1}/${language.downloadSources.size}, attempt $attempt/$ATTEMPTS_PER_SOURCE")
                        fetch(source, archive, onProgress)
                        onProgress("Verifying model archive…")
                        if (!checksumMatches(archive, language.sha256)) {
                            archive.delete() // Never resume from an archive with an incorrect checksum.
                            throw IOException("Model checksum mismatch")
                        }
                        break@downloadLoop
                    } catch (error: IOException) {
                        failures.add("${URL(source).host}: ${error.message ?: error.javaClass.simpleName}")
                        onProgress(if (attempt == ATTEMPTS_PER_SOURCE) "Trying another source…" else "Connection interrupted; retrying…")
                    }
                }
            }
            if (!checksumMatches(archive, language.sha256)) {
                throw IOException("All sources failed. ${failures.lastOrNull() ?: "Unknown error"}. " +
                    "Check your connection or import the model ZIP manually.")
            }
        }
        install(context, language, archive, onProgress)
        archive.delete()
    }

    /** Android Storage Access Framework fallback when download servers are unavailable. */
    fun importArchive(context: Context, language: ModelLanguage, uri: Uri, onProgress: (String) -> Unit) {
        if (isInstalled(context, language)) return
        val archive = partialFile(context, language)
        archive.delete()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected file")
            input.use { stream ->
                FileOutputStream(archive).buffered().use { output ->
                    copyLimited(stream, output, onProgress)
                }
            }
            onProgress("Verifying imported ZIP…")
            if (!checksumMatches(archive, language.sha256)) {
                throw IOException("Incorrect or damaged model ZIP (SHA-256 mismatch)")
            }
            install(context, language, archive, onProgress)
        } finally {
            archive.delete()
        }
    }

    private fun partialFile(context: Context, language: ModelLanguage): File {
        val dir = File(context.filesDir, "models/.downloads")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create model download directory" }
        return File(dir, "${language.code}.zip.part")
    }

    private fun fetch(source: String, archive: File, onProgress: (String) -> Unit) {
        val offset = archive.takeIf { it.isFile }?.length() ?: 0L
        if (offset > MAX_ARCHIVE) {
            archive.delete()
            throw IOException("Partial archive exceeds size limit")
        }
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000 // per stalled read, not a 60-second total download limit
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val response = connection.responseCode
            if (response != 200 && response != 206) throw IOException("HTTP $response")
            val append = offset > 0L && response == 206
            if (append) {
                val range = connection.getHeaderField("Content-Range") ?: ""
                if (!range.startsWith("bytes $offset-")) {
                    throw IOException("Unexpected Content-Range response")
                }
            }
            // Servers which ignore Range return 200: safely restart rather than corrupt the ZIP.
            val startingSize = if (append) offset else 0L
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ARCHIVE ||
                (contentLength >= 0L && startingSize + contentLength > MAX_ARCHIVE)) {
                throw IOException("Model archive is too large")
            }
            val expectedSize = if (contentLength >= 0L) startingSize + contentLength else -1L
            var received = startingSize
            var previousUpdate = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(archive, append).buffered().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MAX_ARCHIVE) throw IOException("Archive exceeded size limit")
                        output.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - previousUpdate > 800L) {
                            val currentMb = received / 1024 / 1024
                            val total = if (expectedSize > 0L) " / ${expectedSize / 1024 / 1024} MB" else " MB"
                            onProgress("Downloading: $currentMb$total")
                            previousUpdate = now
                        }
                    }
                }
            }
            if (expectedSize >= 0L && received != expectedSize) {
                throw EOFException("Connection closed after $received of $expectedSize bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, progress: (String) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var lastReportedMb = -1L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ARCHIVE) throw IOException("Imported archive is too large")
            output.write(buffer, 0, count)
            val megabytes = total / 1024 / 1024
            if (megabytes % 4 == 0L && megabytes != lastReportedMb) {
                progress("Importing: $megabytes MB")
                lastReportedMb = megabytes
            }
        }
    }

    private fun checksumMatches(file: File, expected: String): Boolean {
        if (!file.isFile || file.length() == 0L || file.length() > MAX_ARCHIVE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
            .equals(expected, ignoreCase = true)
    }

    private fun install(context: Context, language: ModelLanguage, archive: File, onProgress: (String) -> Unit) {
        if (isInstalled(context, language)) return
        val dest = destination(context, language)
        val parent = dest.parentFile ?: error("Model directory unavailable")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create model directory" }
        val stage = File(parent, "${language.code}.tmp")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create staging directory" }
        try {
            onProgress("Extracting model…")
            var expanded = 0L
            var entries = 0
            ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++entries <= 10_000) { "Too many ZIP entries" }
                    val components = entry.name.replace('\\', '/').split('/')
                        .filter { it.isNotEmpty() }
                    check(components.isNotEmpty() && components.none { it == "." || it == ".." }) {
                        "Unsafe ZIP entry"
                    }
                    // Vosk archives have one common root directory.
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
                                "Cannot create parent directory"
                            }
                            target.outputStream().buffered().use { output ->
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    expanded += count
                                    check(expanded <= MAX_EXTRACTED) { "Extracted model is too large" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    if (entries % 25 == 0) onProgress("Extracting: $entries files")
                }
            }
            check(File(stage, "am").isDirectory && File(stage, "conf").isDirectory) {
                "Invalid Vosk model ZIP"
            }
            check(File(stage, ".ready").createNewFile()) { "Cannot finalize model" }
            if (dest.exists()) {
                if (isInstalled(context, language)) return
                check(dest.deleteRecursively()) { "Cannot replace incomplete model" }
            }
            check(stage.renameTo(dest)) { "Cannot install model" }
            onProgress("Model ready")
        } finally {
            stage.deleteRecursively()
        }
    }
}
