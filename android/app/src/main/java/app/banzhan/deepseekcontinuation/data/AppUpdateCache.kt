package app.banzhan.deepseekcontinuation.data

import android.content.Context
import android.util.AtomicFile
import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import app.banzhan.deepseekcontinuation.core.AppUpdateManifestParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class AppUpdateCache(context: Context) {
    private val cacheRoot = context.cacheDir.canonicalFile
    private val directory = File(cacheRoot, "app-updates")
    private val metadata = AtomicFile(File(directory, "manifest.json"))

    fun restoreManifest(): AppUpdateManifest? {
        ensureDirectory()
        directory.listFiles()?.filter { it.name.endsWith(".part") && it.isFile }?.forEach { it.delete() }
        return try {
            metadata.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0 || output.size() + count > AppUpdateManifestParser.MAX_MANIFEST_BYTES) return null
                    output.write(buffer, 0, count)
                }
                AppUpdateManifestParser.parse(output.toByteArray())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveManifest(manifest: AppUpdateManifest) {
        ensureDirectory()
        var output: java.io.FileOutputStream? = null
        try {
            output = metadata.startWrite()
            output.write(updateJson.encodeToString(AppUpdateManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            metadata.finishWrite(output)
        } catch (error: Exception) {
            metadata.failWrite(output)
            throw AppUpdateDownloadException("无法保存更新信息，请稍后重试。")
        }
    }

    fun newPart(): File {
        ensureDirectory()
        return File(directory, "download-${UUID.randomUUID()}.part")
    }

    fun apk(manifest: AppUpdateManifest): File {
        ensureDirectory()
        return File(directory, "${manifest.versionCode}-${manifest.sha256}.apk")
    }

    fun promote(part: File, manifest: AppUpdateManifest): File {
        val destination = apk(manifest)
        if (part.canonicalFile.parentFile != directory.canonicalFile || !part.name.endsWith(".part")) {
            throw AppUpdateDownloadException("更新缓存无效，请重新下载。")
        }
        try {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            throw AppUpdateDownloadException("无法保存更新包，请重新下载。")
        }
        return destination
    }

    fun discardOtherApks(keep: File) {
        directory.listFiles()?.filter { it.name.endsWith(".apk") && it != keep && it.isFile }?.forEach { it.delete() }
    }

    private fun ensureDirectory() {
        if (directory.canonicalFile.parentFile != cacheRoot || directory.canonicalFile.name != "app-updates" ||
            (!directory.isDirectory && !directory.mkdirs())) {
            throw AppUpdateDownloadException("无法创建更新缓存，请稍后重试。")
        }
    }
}
