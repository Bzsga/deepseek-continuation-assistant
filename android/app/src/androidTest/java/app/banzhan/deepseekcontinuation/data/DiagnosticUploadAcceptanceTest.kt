package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network acceptance: synthetic technical metadata only, no model endpoint. */
@RunWith(AndroidJUnit4::class)
class DiagnosticUploadAcceptanceTest {
    @Test fun syntheticTechnicalEventReachesPublicEndpointAndIsAcknowledged() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("diagnosticNetworkAcceptance") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppController::class.java]
        }
        var original: ServiceSettings? = null
        try {
            DiagnosticLog.initialize(application)
            controller.revisions("diagnostic-acceptance-nonexistent") // Wait for local settings to load.
            val before = controller.state.value.settings
            original = before
            assertEquals("https://digimonlegend.com/continuation-api", before.companionUrl.trimEnd('/'))
            if (before.mode != "companion") controller.saveSettings(before.copy(mode = "companion"))
            // Reuse the same installation identity. This may GET quota / create one
            // anonymous session, but never submits a job. Restore the QA mode below.
            withTimeout(40_000) { controller.refreshQuota() }
            val settings = controller.state.value.settings
            assertEquals("companion", settings.mode)
            assertEquals("https://digimonlegend.com/continuation-api", settings.companionUrl.trimEnd('/'))
            assertTrue(settings.trialToken.isNotBlank())
            val id = requireNotNull(DiagnosticLog.record("lifecycle", "recovered", mapOf("input_bytes" to 321L)))
            val file = File(application.noBackupFilesDir, "diagnostics/$id.json")
            assertTrue(file.exists())
            DiagnosticLog.bindAnonymousIdentity(settings.mode, settings.companionUrl, settings.trialToken)
            withTimeout(65_000) {
                while (file.exists()) {
                    DiagnosticLog.requestUpload()
                    delay(1_000)
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("diagnostic_event_id", id) })
        } finally {
            original?.let {
                if (controller.state.value.settings.mode != it.mode) controller.saveSettings(it)
                DiagnosticLog.bindAnonymousIdentity(it.mode, it.companionUrl, it.trialToken)
            }
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
