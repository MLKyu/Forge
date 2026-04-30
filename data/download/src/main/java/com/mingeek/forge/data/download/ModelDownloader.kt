package com.mingeek.forge.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) {

    fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
    ): Flow<DownloadProgress> = callbackFlow {
        val partial = File(target.parentFile, "${target.name}.part")
        target.parentFile?.mkdirs()

        val resumeFrom = if (partial.exists()) partial.length() else 0L
        trySend(DownloadProgress.Started)

        val requestBuilder = Request.Builder().url(url)
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        val call = client.newCall(requestBuilder.build())
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    trySend(DownloadProgress.Failed("HTTP ${response.code}"))
                    close()
                    return@use
                }

                val body = response.body ?: run {
                    trySend(DownloadProgress.Failed("empty body"))
                    close()
                    return@use
                }

                val totalContentLength = body.contentLength().takeIf { it >= 0 }
                val totalBytes = if (response.code == 206 && totalContentLength != null) {
                    totalContentLength + resumeFrom
                } else {
                    totalContentLength
                }

                val raf = RandomAccessFile(partial, "rw")
                raf.use { out ->
                    if (response.code == 206) out.seek(resumeFrom) else out.setLength(0)

                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var written = if (response.code == 206) resumeFrom else 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastEmit > 256 * 1024) {
                                trySend(DownloadProgress.Progress(written, totalBytes))
                                lastEmit = written
                            }
                        }
                        trySend(DownloadProgress.Progress(written, totalBytes))
                    }
                }

                if (expectedSha256 != null) {
                    trySend(DownloadProgress.Verifying(expectedSha256))
                    val actual = sha256(partial)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        partial.delete()
                        trySend(DownloadProgress.Failed("SHA-256 mismatch (expected $expectedSha256, got $actual)"))
                        close()
                        return@use
                    }
                }

                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    trySend(DownloadProgress.Failed("rename failed: ${partial.path} -> ${target.path}"))
                    close()
                    return@use
                }
                trySend(DownloadProgress.Completed(target, expectedSha256))
            }
        } catch (io: IOException) {
            trySend(DownloadProgress.Failed(io.message ?: "I/O error", io))
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
