package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.AppUpdateManifest

enum class AppUpdateStatus { IDLE, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }

data class AppUpdateState(
    val checking: Boolean = false,
    val manifest: AppUpdateManifest? = null,
    val status: AppUpdateStatus = AppUpdateStatus.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val message: String? = null,
    val promptVisible: Boolean = false,
)
