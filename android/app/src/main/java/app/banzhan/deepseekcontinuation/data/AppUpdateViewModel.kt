package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val network = AppUpdateNetwork()
    private val cache = AppUpdateCache(application)
    private val downloads: AppUpdateDownloads = SystemAppUpdateDownloads(application)
    private val verifier = AppUpdateVerifier(application)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private var lastAutomaticCheck: Long? = null
    private var dismissedVersion: Int? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var checkGeneration = 0L
    @Volatile private var downloadGeneration = 0L
    private var checkingForInstall = false

    init {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val cachedManifest = cache.restoreManifest()
                    var pending = downloads.restore()
                    if (pending != null && !available(pending.manifest)) {
                        downloads.remove(pending)
                        pending = null
                    }
                    val manifest = pending?.manifest ?: cachedManifest
                    if (manifest == null || !available(manifest)) return@withContext null
                    val file = cache.apk(manifest)
                    val verified = restoreVerifiedFile(file, manifest)
                    if (verified && pending != null) {
                        downloads.remove(pending)
                        pending = null
                    }
                    Triple(manifest, verified, pending)
                }
                if (restored != null) {
                    val (manifest, verified, pending) = restored
                    mutableState.value = AppUpdateState(
                        manifest = manifest,
                        status = when {
                            verified -> AppUpdateStatus.READY
                            pending != null -> AppUpdateStatus.DOWNLOADING
                            else -> AppUpdateStatus.AVAILABLE
                        },
                        downloadedBytes = if (verified) manifest.apkSize else 0,
                        totalBytes = manifest.apkSize,
                        promptVisible = true,
                    )
                    if (pending != null) observeDownload(manifest, pending)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Startup cache recovery is optional and must not interrupt the user's current work.
            } finally {
                ready.complete(Unit)
            }
        }
    }

    fun checkOnStart() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutomaticCheck?.let { now - it < 5 * 60 * 1000L } == true) return
        if (checkingForInstall || mutableState.value.status in setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING)) return
        lastAutomaticCheck = now
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (checkingForInstall || mutableState.value.status in setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING)) return
        val generation = ++checkGeneration
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            ready.await()
            // Startup restoration may have recovered a system download while this check waited.
            if (checkingForInstall || mutableState.value.status in setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING)) return@launch
            mutableState.update { it.copy(checking = true, error = if (manual) null else it.error, message = null) }
            try {
                val manifest = network.fetchManifest()
                val installed = withContext(Dispatchers.IO) { verifier.installedVersionCode() }
                if (generation != checkGeneration) return@launch
                if (manifest.versionCode.toLong() <= installed) {
                    mutableState.value = AppUpdateState(message = if (manual) "已是最新版本" else null)
                } else if (manifest.minSdk > Build.VERSION.SDK_INT) {
                    mutableState.value = AppUpdateState(message = if (manual) "新版本暂不支持当前 Android 版本" else null)
                } else {
                    val previous = mutableState.value
                    val sameReadyPackage = previous.status == AppUpdateStatus.READY && previous.manifest == manifest
                    mutableState.value = AppUpdateState(
                        manifest = manifest,
                        status = if (sameReadyPackage) AppUpdateStatus.READY else AppUpdateStatus.AVAILABLE,
                        downloadedBytes = if (sameReadyPackage) manifest.apkSize else 0,
                        totalBytes = manifest.apkSize,
                        promptVisible = manual || dismissedVersion != manifest.versionCode,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == checkGeneration) mutableState.update {
                    it.copy(checking = false, error = if (manual) safeMessage(error, "暂时无法检查更新，请稍后重试。") else it.error)
                }
            } finally {
                if (generation == checkGeneration) mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun downloadUpdate() {
        val current = mutableState.value
        val manifest = current.manifest ?: return
        if (checkingForInstall || current.status in setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING, AppUpdateStatus.READY)) return
        ++checkGeneration
        checkJob?.cancel()
        observeDownload(manifest, replaceCompleted = current.status == AppUpdateStatus.FAILED)
    }

    private fun observeDownload(manifest: AppUpdateManifest, restored: AppUpdateDownload? = null, replaceCompleted: Boolean = false) {
        val generation = ++downloadGeneration
        val previous = downloadJob
        mutableState.update { it.copy(checking = false, status = AppUpdateStatus.DOWNLOADING, downloadedBytes = 0,
            totalBytes = manifest.apkSize, error = null, message = null) }
        downloadJob = viewModelScope.launch {
            var part: File? = null
            var promoted: File? = null
            var verified = false
            try {
                // A new explicit retry waits for prior cancellation cleanup before enqueuing.
                previous?.join()
                ready.await()
                val download = restored ?: withContext(Dispatchers.IO) {
                    if (!available(manifest)) throw AppUpdateVerificationException("当前更新已不适用，请重新检查更新。")
                    cache.saveManifest(manifest)
                    val pending = downloads.restore()
                    if (pending != null) {
                        val progress = downloads.progress(pending)
                        if (pending.manifest == manifest && progress.stage !in setOf(AppUpdateDownloadStage.FAILED, AppUpdateDownloadStage.MISSING) &&
                            !(replaceCompleted && progress.stage == AppUpdateDownloadStage.COMPLETED)) {
                            return@withContext pending
                        }
                        downloads.remove(pending)
                    }
                    downloads.start(manifest)
                }
                while (true) {
                    ensureActive()
                    val progress = withContext(Dispatchers.IO) { downloads.progress(download) }
                    if (progress.stage == AppUpdateDownloadStage.COMPLETED) break
                    if (progress.stage in setOf(AppUpdateDownloadStage.FAILED, AppUpdateDownloadStage.MISSING)) {
                        throw AppUpdateDownloadException(progress.message ?: "更新下载已停止，请重试。")
                    }
                    mutableState.update {
                        if (generation == downloadGeneration && it.status == AppUpdateStatus.DOWNLOADING) {
                            it.copy(downloadedBytes = progress.downloadedBytes.coerceIn(0, manifest.apkSize),
                                totalBytes = manifest.apkSize,
                                message = if (progress.stage == AppUpdateDownloadStage.WAITING) progress.message ?: "等待网络恢复，系统会继续下载。" else null)
                        } else it
                    }
                    delay(750)
                }
                ensureActive()
                if (generation != downloadGeneration) return@launch
                mutableState.update { it.copy(status = AppUpdateStatus.VERIFYING) }
                withContext(Dispatchers.IO) {
                    part = cache.newPart()
                    downloads.copyTo(download, requireNotNull(part))
                    val file = cache.promote(requireNotNull(part), manifest)
                    promoted = file
                    verifier.verify(file, manifest)
                }
                ensureActive()
                if (generation != downloadGeneration) return@launch
                verified = true
                mutableState.update { it.copy(status = AppUpdateStatus.READY, downloadedBytes = manifest.apkSize,
                    totalBytes = manifest.apkSize, error = null, message = null) }
                withContext(Dispatchers.IO) {
                    // A verified private APK remains ready even if system-cache cleanup is delayed.
                    runCatching { downloads.remove(download) }
                    cache.discardOtherApks(requireNotNull(promoted))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == downloadGeneration) mutableState.update {
                    it.copy(status = AppUpdateStatus.FAILED, error = safeMessage(error, "更新包下载失败，请重试。"))
                }
            } finally {
                part?.delete()
                if (!verified) promoted?.delete()
            }
        }
    }

    fun cancelDownload() {
        if (mutableState.value.status !in setOf(AppUpdateStatus.DOWNLOADING, AppUpdateStatus.VERIFYING)) return
        val generation = ++downloadGeneration
        val previous = downloadJob
        downloadJob = viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    downloads.restore()?.let(downloads::remove)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == downloadGeneration) mutableState.update {
                    it.copy(status = AppUpdateStatus.FAILED, error = "暂时无法取消系统下载，请稍后重试。")
                }
            }
        }
        mutableState.update { it.copy(status = AppUpdateStatus.AVAILABLE, downloadedBytes = 0, error = null,
            message = "已取消下载，可稍后重试。") }
    }

    fun dismissForSession() {
        dismissedVersion = mutableState.value.manifest?.versionCode
        mutableState.update { it.copy(promptVisible = false) }
    }

    /** The caller owns the foreground/busy and unknown-source permission gates, then opens the installer. */
    suspend fun verifiedFileForInstall(): File {
        ready.await()
        val current = mutableState.value
        val manifest = current.manifest
        if (checkingForInstall || current.status != AppUpdateStatus.READY || manifest == null) {
            val message = "请先完成更新包下载和校验。"
            mutableState.update { it.copy(error = message) }
            throw AppUpdateVerificationException(message)
        }
        checkingForInstall = true
        ++checkGeneration
        checkJob?.cancel()
        mutableState.update { it.copy(checking = false, error = null) }
        try {
            return withContext(Dispatchers.IO) { verifier.verify(cache.apk(manifest), manifest) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = safeMessage(error, "更新包验证失败，请重新下载。")
            mutableState.update { it.copy(status = AppUpdateStatus.FAILED, error = message) }
            throw AppUpdateVerificationException(message)
        } finally {
            checkingForInstall = false
        }
    }

    private fun available(manifest: AppUpdateManifest): Boolean =
        manifest.versionCode.toLong() > verifier.installedVersionCode() && manifest.minSdk <= Build.VERSION.SDK_INT

    private fun restoreVerifiedFile(file: File, manifest: AppUpdateManifest): Boolean {
        if (!file.isFile) return false
        return try {
            verifier.verify(file, manifest)
            true
        } catch (_: AppUpdateVerificationException) {
            file.delete()
            false
        }
    }

    private fun safeMessage(error: Exception, fallback: String): String = when (error) {
        is AppUpdateDownloadException, is AppUpdateVerificationException -> error.message ?: fallback
        else -> fallback
    }
}
