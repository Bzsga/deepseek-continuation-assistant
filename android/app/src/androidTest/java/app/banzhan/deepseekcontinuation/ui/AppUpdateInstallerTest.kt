package app.banzhan.deepseekcontinuation.ui

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installerSharesOnlyTheOwnPrivateUpdateFileReadOnly() {
        val file = File(context.cacheDir, "app-updates/installer-fixture.apk")
        file.parentFile!!.mkdirs()
        file.writeText("fixture - not installed")
        try {
            val intent = AppUpdateInstaller.installationIntent(context, file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals(context.packageName + ".files", intent.data?.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
        } finally {
            file.delete()
        }
    }

    @Test
    fun externalPathsAndPartialFilesAreRejected() {
        val outside = File(context.cacheDir, "outside-update.apk")
        val partial = File(context.cacheDir, "app-updates/partial.part")
        outside.writeText("private outside file")
        partial.parentFile!!.mkdirs()
        partial.writeText("incomplete")
        try {
            listOf(outside, partial).forEach { file ->
                try {
                    AppUpdateInstaller.installationIntent(context, file)
                    fail("Unexpected installer grant")
                } catch (_: IllegalArgumentException) {}
            }
        } finally {
            outside.delete()
            partial.delete()
        }
    }

    @Test
    fun permissionRequestTargetsThisApplicationOnly() {
        val intent = AppUpdateInstaller.permissionIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:" + context.packageName, intent.data.toString())
    }
}
