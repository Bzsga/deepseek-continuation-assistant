package app.banzhan.deepseekcontinuation.data

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host runs these phases separately, killing only the isolated QA app between them. */
@RunWith(AndroidJUnit4::class)
class AppUpdateBackgroundDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun startPersistentDownload() = runBlocking {
        requireLiveProbe()
        val downloads = SystemAppUpdateDownloads(context)
        val record = downloads.restore() ?: downloads.start(AppUpdateNetwork().fetchManifest())
        assertEquals(record.id, downloads.start(record.manifest).id)
        val progress = downloads.progress(record)
        assertTrue(progress.stage in setOf(AppUpdateDownloadStage.RUNNING, AppUpdateDownloadStage.WAITING))
        assertNotEquals("Host throttles the dedicated emulator for the background interval", AppUpdateDownloadStage.COMPLETED, progress.stage)
        context.getSharedPreferences("update-background-qa", 0).edit()
            .putLong("downloadId", record.id).putLong("beforeBytes", progress.downloadedBytes).commit()
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("downloadId", record.id.toString())
            putString("beforeBytes", progress.downloadedBytes.toString())
        })
    }

    @Test
    fun recoverSameDownloadAndVerifyPrivateCopy() {
        requireLiveProbe()
        val saved = context.getSharedPreferences("update-background-qa", 0)
        val expectedId = saved.getLong("downloadId", 0)
        val before = saved.getLong("beforeBytes", 0)
        assertTrue(expectedId > 0 && before >= 0)
        val downloads = SystemAppUpdateDownloads(context)
        val record = requireNotNull(downloads.restore())
        assertEquals(expectedId, record.id)
        var progress = downloads.progress(record)
        assertTrue("Transfer must progress while the QA app process is absent", progress.downloadedBytes > before)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("downloadId", record.id.toString())
            putString("beforeBytes", before.toString())
            putString("recoveredBytes", progress.downloadedBytes.toString())
        })
        val deadline = SystemClock.elapsedRealtime() + 150_000
        while (progress.stage != AppUpdateDownloadStage.COMPLETED && SystemClock.elapsedRealtime() < deadline) {
            assertFalse(progress.stage in setOf(AppUpdateDownloadStage.FAILED, AppUpdateDownloadStage.MISSING))
            SystemClock.sleep(500)
            progress = downloads.progress(record)
        }
        assertEquals(AppUpdateDownloadStage.COMPLETED, progress.stage)
        val cache = AppUpdateCache(context)
        val part = cache.newPart()
        var apk: File? = null
        try {
            downloads.copyTo(record, part)
            apk = cache.promote(part, record.manifest)
            assertEquals(apk, verifyAppUpdateFile(apk, context.cacheDir, record.manifest))
            // The isolated QA package differs from the production package. It cannot install this APK.
            assertThrows(AppUpdateVerificationException::class.java) {
                AppUpdateVerifier(context).verify(apk, record.manifest)
            }
            downloads.remove(record)
            assertNull(downloads.restore())
        } finally {
            part.delete()
            apk?.delete()
            saved.edit().clear().commit()
        }
    }

    private fun requireLiveProbe() {
        assumeTrue("Live static-file testing must be explicitly enabled on an isolated QA package",
            InstrumentationRegistry.getArguments().getString("liveUpdateProbe") == "true" && context.packageName.endsWith(".updateqa"))
    }
}
