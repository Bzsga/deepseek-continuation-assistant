package app.banzhan.deepseekcontinuation.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/** Initial location is a hint; unsupported providers keep the system picker fallback. */
internal class ImportDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(
                    "com.android.providers.downloads.documents",
                    "downloads",
                ),
            )
        }
}
