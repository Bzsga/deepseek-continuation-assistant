package app.banzhan.deepseekcontinuation.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import app.banzhan.deepseekcontinuation.core.AppUpdateManifestParser
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
internal data class AppUpdateDownload(val manifest: AppUpdateManifest, val id: Long, val token: String)

internal enum class AppUpdateDownloadStage { RUNNING, WAITING, COMPLETED, FAILED, MISSING }

internal data class AppUpdateDownloadProgress(
    val stage: AppUpdateDownloadStage,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val message: String? = null,
)

internal interface AppUpdateDownloads {
    fun restore(): AppUpdateDownload?
    fun start(manifest: AppUpdateManifest): AppUpdateDownload
    fun progress(download: AppUpdateDownload): AppUpdateDownloadProgress
    fun copyTo(download: AppUpdateDownload, part: File)
    fun remove(download: AppUpdateDownload)
}

/** The system owns network work; this object only observes and verifies a particular request identity. */
internal class SystemAppUpdateDownloads(context: Context) : AppUpdateDownloads {
    private val app = context.applicationContext
    private val delegate = PersistentAppUpdateDownloads(
        AndroidAppUpdateDownloadPlatform(app), AtomicAppUpdateDownloadStore(app), File(app.cacheDir, "app-updates"),
    )
    override fun restore() = delegate.restore()
    override fun start(manifest: AppUpdateManifest) = delegate.start(manifest)
    override fun progress(download: AppUpdateDownload) = delegate.progress(download)
    override fun copyTo(download: AppUpdateDownload, part: File) = delegate.copyTo(download, part)
    override fun remove(download: AppUpdateDownload) = delegate.remove(download)
}

internal data class AppUpdatePlatformDownload(
    val id: Long,
    val url: String,
    val token: String?,
    val stage: AppUpdateDownloadStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val message: String? = null,
    val filePresent: Boolean = true,
    val destinationPending: Boolean = false,
)

internal interface AppUpdateDownloadPlatform {
    fun enqueue(manifest: AppUpdateManifest, token: String): Long
    fun find(manifest: AppUpdateManifest, token: String): List<AppUpdatePlatformDownload>
    fun query(id: Long): AppUpdatePlatformDownload?
    fun open(id: Long): InputStream
    fun remove(id: Long)
    fun discard(token: String)
}

internal interface AppUpdateDownloadStore {
    fun load(): AppUpdateDownload?
    fun save(download: AppUpdateDownload)
    fun clear()
}

/** Also used with a synthetic platform/store in local tests. No Android lifecycle owns these tasks. */
internal class PersistentAppUpdateDownloads(
    private val platform: AppUpdateDownloadPlatform,
    private val store: AppUpdateDownloadStore,
    private val privateParts: File,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) : AppUpdateDownloads {
    override fun restore(): AppUpdateDownload? = synchronized(downloadLock) { safely { restoreLocked() } }

    private fun restoreLocked(): AppUpdateDownload? {
        val saved = store.load()?.validated() ?: return null
        if (saved.id > 0) return saved
        val candidates = platform.find(saved.manifest, saved.token).filter { matches(it, saved) }
        if (candidates.size > 1) throw AppUpdateDownloadException("更新下载记录不明确，请取消后重试。")
        val found = candidates.singleOrNull() ?: return saved
        return saved.copy(id = found.id).also(store::save)
    }

    override fun start(manifest: AppUpdateManifest): AppUpdateDownload = synchronized(downloadLock) { safely {
        val checked = validateManifest(manifest)
        val previous = restoreLocked()
        if (previous != null) {
            val status = progressLocked(previous).stage
            if (status in setOf(AppUpdateDownloadStage.RUNNING, AppUpdateDownloadStage.WAITING, AppUpdateDownloadStage.COMPLETED)) {
                if (previous.manifest != checked) throw AppUpdateDownloadException("已有更新正在下载，请先取消该下载。")
                return@safely previous
            }
            removeLocked(previous)
        }
        // Persist the unique destination first. If enqueue succeeds but its response/save is lost,
        // restore finds that exact app-owned URL/destination instead of enqueuing a second request.
        val intent = AppUpdateDownload(checked, 0, newToken()).validated()
        store.save(intent)
        val id = platform.enqueue(checked, intent.token)
        if (id <= 0) throw AppUpdateDownloadException("系统未能创建下载任务，请稍后重试。")
        intent.copy(id = id).also(store::save)
    } }

    override fun progress(download: AppUpdateDownload): AppUpdateDownloadProgress = synchronized(downloadLock) {
        safely { progressLocked(download.validated()) }
    }

    private fun progressLocked(download: AppUpdateDownload): AppUpdateDownloadProgress {
        val row = if (download.id > 0) platform.query(download.id) else null
        if (row == null || !matches(row, download, allowPendingDestination = true)) return AppUpdateDownloadProgress(
            AppUpdateDownloadStage.MISSING, 0, download.manifest.apkSize, "下载任务已丢失，请重新下载。",
        )
        if (row.downloadedBytes > download.manifest.apkSize || (row.totalBytes > 0 && row.totalBytes != download.manifest.apkSize)) {
            return AppUpdateDownloadProgress(AppUpdateDownloadStage.FAILED, 0, download.manifest.apkSize,
                "更新包大小与更新信息不一致，请重新下载。")
        }
        if (row.stage == AppUpdateDownloadStage.COMPLETED && !row.filePresent) return AppUpdateDownloadProgress(
            AppUpdateDownloadStage.MISSING, 0, download.manifest.apkSize, "下载文件已丢失，请重新下载。",
        )
        return AppUpdateDownloadProgress(row.stage, row.downloadedBytes.coerceIn(0, download.manifest.apkSize),
            download.manifest.apkSize, row.message)
    }

    override fun copyTo(download: AppUpdateDownload, part: File) = synchronized(downloadLock) { safely {
        val checked = download.validated()
        if (progressLocked(checked).stage != AppUpdateDownloadStage.COMPLETED) {
            throw AppUpdateDownloadException("更新包尚未下载完成，请稍候。")
        }
        val target = part.canonicalFile
        if (target.parentFile != privateParts.canonicalFile || target.name != part.name || !target.name.endsWith(".part")) {
            throw AppUpdateDownloadException("更新缓存无效，请重新下载。")
        }
        var complete = false
        try {
            var count = 0L
            platform.open(checked.id).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0 || count + read > checked.manifest.apkSize || count + read > AppUpdateManifestParser.MAX_APK_BYTES) {
                            throw AppUpdateDownloadException("更新包大小校验失败，请重新下载。")
                        }
                        output.write(buffer, 0, read)
                        count += read
                    }
                }
            }
            if (count != checked.manifest.apkSize) throw AppUpdateDownloadException("更新包未完整下载，请重试。")
            complete = true
        } finally {
            if (!complete) target.delete()
        }
        // The DownloadManager row/record deliberately survive copy errors and successful copying.
        // The caller only removes them after its hash/package/signature verification or explicit cancel.
    } }

    override fun remove(download: AppUpdateDownload) = synchronized(downloadLock) { safely { removeLocked(download.validated()) } }

    private fun removeLocked(download: AppUpdateDownload) {
        val rows = if (download.id > 0) listOfNotNull(platform.query(download.id))
            else platform.find(download.manifest, download.token)
        rows.filter { matches(it, download, allowPendingDestination = true) }.forEach { platform.remove(it.id) }
        platform.discard(download.token)
        val current = store.load()
        if (current?.id == download.id && current.token == download.token) store.clear()
    }

    private fun matches(row: AppUpdatePlatformDownload, download: AppUpdateDownload, allowPendingDestination: Boolean = false): Boolean =
        row.id > 0 && row.url == download.manifest.apkUrl && (row.token == download.token ||
            (allowPendingDestination && row.id == download.id && row.token == null && row.destinationPending &&
                row.stage in setOf(AppUpdateDownloadStage.RUNNING, AppUpdateDownloadStage.WAITING)))

    private fun AppUpdateDownload.validated(): AppUpdateDownload {
        if (id < 0 || !validToken(token)) throw AppUpdateDownloadException("更新下载记录无效，请重新下载。")
        return copy(manifest = validateManifest(manifest))
    }
}

private class AtomicAppUpdateDownloadStore(context: Context) : AppUpdateDownloadStore {
    private val root = context.noBackupFilesDir.canonicalFile
    private val directory = File(root, "app-updates")
    private val record = AtomicFile(File(directory, "download.json"))

    override fun load(): AppUpdateDownload? {
        ensureDirectory()
        val bytes = try { record.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_RECORD_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RECORD_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count == 0) throw java.io.IOException()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } }
            catch (_: FileNotFoundException) { return null }
        return try {
            if (bytes.size > MAX_RECORD_BYTES) throw IllegalArgumentException()
            val value = updateJson.decodeFromString(AppUpdateDownload.serializer(), bytes.toString(Charsets.UTF_8))
            if (value.id < 0 || !validToken(value.token)) throw IllegalArgumentException()
            value.copy(manifest = validateManifest(value.manifest))
        } catch (_: Exception) {
            // Invalid local task metadata cannot select a system download or become executable code.
            record.delete()
            null
        }
    }

    override fun save(download: AppUpdateDownload) {
        ensureDirectory()
        val bytes = updateJson.encodeToString(AppUpdateDownload.serializer(), download).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORD_BYTES) throw AppUpdateDownloadException("更新下载记录过大，请重新检查更新。")
        var stream: java.io.FileOutputStream? = null
        try {
            stream = record.startWrite(); stream.write(bytes); record.finishWrite(stream)
        } catch (error: Exception) { record.failWrite(stream); throw error }
    }
    override fun clear() = record.delete()
    private fun ensureDirectory() {
        if (directory.canonicalFile.parentFile != root || (!directory.isDirectory && !directory.mkdirs())) {
            throw AppUpdateDownloadException("无法保存更新任务，请稍后重试。")
        }
    }
}

private class AndroidAppUpdateDownloadPlatform(private val context: Context) : AppUpdateDownloadPlatform {
    private fun manager(): DownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        ?: throw AppUpdateDownloadException("系统下载服务不可用，请启用系统下载管理器后重试。")

    private fun directory(create: Boolean): File {
        val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile
            ?: throw AppUpdateDownloadException("下载存储暂不可用，请稍后重试。")
        val result = File(downloads, "app-updates")
        if (result.canonicalFile.parentFile != downloads || (create && !result.isDirectory && !result.mkdirs())) {
            throw AppUpdateDownloadException("无法创建更新下载目录，请稍后重试。")
        }
        return result
    }

    @Suppress("DEPRECATION")
    override fun enqueue(manifest: AppUpdateManifest, token: String): Long {
        if (!validToken(token)) throw AppUpdateDownloadException("更新任务无效，请重新下载。")
        val target = File(directory(true), "$token.part")
        if (target.exists()) throw AppUpdateDownloadException("更新下载文件已存在，请重试。")
        val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
            .setDestinationUri(Uri.fromFile(target))
            .setTitle("Deepseek续聊助手更新")
            .setDescription("正在下载更新包，可切换到其他应用。")
            .setMimeType("application/octet-stream")
            .addRequestHeader("Accept", "application/octet-stream")
            .addRequestHeader("Accept-Encoding", "identity")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setVisibleInDownloadsUi(false)
        return manager().enqueue(request)
    }

    override fun query(id: Long): AppUpdatePlatformDownload? = rows(DownloadManager.Query().setFilterById(id)).singleOrNull()
    override fun find(manifest: AppUpdateManifest, token: String): List<AppUpdatePlatformDownload> =
        rows(DownloadManager.Query()).filter { it.url == manifest.apkUrl && it.token == token }

    private fun rows(query: DownloadManager.Query): List<AppUpdatePlatformDownload> {
        val root = directory(false).canonicalFile
        val result = mutableListOf<AppUpdatePlatformDownload>()
        manager().query(query)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty()
                val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val localFile = try {
                    val uri = local?.let(Uri::parse)
                    if (uri == null || uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) null
                    else uri.path?.let(::File)?.canonicalFile?.takeIf { it.parentFile == root }
                } catch (_: Exception) { null }
                val token = localFile?.name?.removeSuffix(".part")?.takeIf { validToken(it) && localFile.name == "$it.part" }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val stage = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> AppUpdateDownloadStage.COMPLETED
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> AppUpdateDownloadStage.WAITING
                    DownloadManager.STATUS_RUNNING -> AppUpdateDownloadStage.RUNNING
                    else -> AppUpdateDownloadStage.FAILED
                }
                val message = when (stage) {
                    AppUpdateDownloadStage.WAITING -> if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
                        "正在等待网络，连接后系统会继续下载。" else "正在等待系统继续下载。"
                    AppUpdateDownloadStage.FAILED -> when (reason) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理空间后重新下载。"
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用，请稍后重试。"
                        else -> "系统下载未完成，请重新下载。"
                    }
                    else -> null
                }
                result += AppUpdatePlatformDownload(id, url, token, stage,
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), message,
                    localFile?.isFile == true,
                    local == null || local.startsWith("content://downloads/"))
            }
        }
        return result
    }

    override fun open(id: Long): InputStream = ParcelFileDescriptor.AutoCloseInputStream(manager().openDownloadedFile(id))
    override fun remove(id: Long) { manager().remove(id) }
    override fun discard(token: String) {
        if (validToken(token)) File(directory(false), "$token.part").delete()
    }
}

private fun validToken(token: String): Boolean = token.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
private fun validateManifest(manifest: AppUpdateManifest): AppUpdateManifest =
    AppUpdateManifestParser.parse(updateJson.encodeToString(AppUpdateManifest.serializer(), manifest))
private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (error: AppUpdateDownloadException) { throw error }
    catch (_: Exception) { throw AppUpdateDownloadException("系统下载暂不可用，请稍后重试。") }
private val downloadLock = Any()
private const val MAX_RECORD_BYTES = 66_560
