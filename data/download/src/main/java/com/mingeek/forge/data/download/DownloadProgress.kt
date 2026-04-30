package com.mingeek.forge.data.download

import java.io.File

sealed interface DownloadProgress {
    data object Started : DownloadProgress

    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : DownloadProgress {
        val fraction: Float? get() = totalBytes?.let { if (it > 0) bytesDownloaded.toFloat() / it else null }
    }

    data class Verifying(val sha256: String) : DownloadProgress

    data class Completed(val file: File, val sha256: String?) : DownloadProgress

    data class Failed(val message: String, val cause: Throwable? = null) : DownloadProgress
}
