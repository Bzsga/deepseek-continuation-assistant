package app.banzhan.deepseekcontinuation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.banzhan.deepseekcontinuation.data.AppController
import app.banzhan.deepseekcontinuation.data.DiagnosticLog
import app.banzhan.deepseekcontinuation.data.IncomingImportViewModel
import app.banzhan.deepseekcontinuation.ui.ContinuationApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller by lazy { ViewModelProvider(this)[AppController::class.java] }
    private val incomingImports by lazy { ViewModelProvider(this)[IncomingImportViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receivedIntent = intent
        // SavedStateHandle default arguments must never come from an external sender's extras.
        clearReceivedIntent()
        showReceiveError(incomingImports.receiveInitial(receivedIntent, restored = savedInstanceState != null))
        lifecycleScope.launch {
            controller.state.map { Triple(it.settings.mode, it.settings.companionUrl, it.settings.trialToken) }
                .distinctUntilChanged().collect { (mode, url, token) -> DiagnosticLog.bindAnonymousIdentity(mode, url, token) }
        }
        enableEdgeToEdge()
        setContent {
            ContinuationApp(
                controller = controller,
                incomingImport = incomingImports.pending,
                onIncomingImportConfirmed = { incomingImports.confirm(it, controller) },
                onIncomingImportConsumed = incomingImports::consume,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showReceiveError(incomingImports.receive(intent))
        clearReceivedIntent()
    }

    override fun onStart() {
        super.onStart()
        DiagnosticLog.requestUpload()
        lifecycleScope.launch { controller.prepareDiagnosticsIdentity() }
    }

    private fun clearReceivedIntent() {
        // Retain no sender-controlled action/extras that could be replayed on recreation.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    private fun showReceiveError(message: String?) {
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
