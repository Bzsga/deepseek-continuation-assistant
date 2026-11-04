package app.banzhan.deepseekcontinuation.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Installation always goes through the Android permission and confirmation screens. */
internal object AppUpdateInstaller {
    fun permissionRequired(context: Context): Boolean =
        !context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Caller must re-verify metadata, bytes and signing certificate immediately before this call.
     */
    fun installationIntent(context: Context, verifiedApk: File): Intent {
        val directory = File(context.cacheDir, "app-updates").canonicalFile
        val file = verifiedApk.canonicalFile
        require(
            file.path.startsWith(directory.path + File.separator) &&
                file.isFile &&
                file.extension == "apk"
        ) {
            "更新包不可用，请重新下载。"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("应用更新", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
