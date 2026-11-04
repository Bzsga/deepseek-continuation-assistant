package app.banzhan.deepseekcontinuation.data

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads and copies only the installed test target APK. Never downloads or invokes an installer. */
@RunWith(AndroidJUnit4::class)
class AppUpdateVerifierDeviceTest {
    @Test
    fun platformPackageInspectionPreservesCurrentSignaturesAndRejectsTheInstalledVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val installed = appUpdatePackageIdentity(context.packageManager.getPackageInfo(context.packageName, flags))
        assertTrue(installed.signerSha256.isNotEmpty())
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val file = File(directory, "verification-${UUID.randomUUID()}.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(file)
            val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags))
            assertEquals(installed, appUpdatePackageIdentity(archive))
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val manifest = AppUpdateManifest(
                schemaVersion = 1,
                packageName = installed.packageName,
                versionCode = installed.versionCode.toInt(),
                versionName = installed.versionName,
                apkUrl = "https://digimonlegend.com/continuation-updates/apks/synthetic.apk",
                apkSize = file.length(),
                sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                minSdk = installed.minSdk,
                releasedAt = "2026-11-04T00:00:00Z",
                changelog = emptyList(),
            )
            val verifier = AppUpdateVerifier(context)
            assertEquals(installed.versionCode, verifier.installedVersionCode())
            val error = assertThrows(AppUpdateVerificationException::class.java) { verifier.verify(file, manifest) }
            assertEquals("当前已安装相同或更高版本，无需安装此更新。", error.message)
        } finally {
            file.delete()
        }
    }
}
