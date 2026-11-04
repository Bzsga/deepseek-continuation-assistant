package app.banzhan.deepseekcontinuation

import android.app.Application
import app.banzhan.deepseekcontinuation.data.DiagnosticLog

class ContinuationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
    }
}
