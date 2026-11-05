package app.banzhan.deepseekcontinuation.ui

internal fun saveReorganizationScreenshot(name: String) {
    val device = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val directory = java.io.File(device.targetContext.getExternalFilesDir(null), "reorganization-ui-qa").apply { mkdirs() }
    val screenshot = device.uiAutomation.takeScreenshot()
    try {
        java.io.File(directory, name).outputStream().use {
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        screenshot.recycle()
    }
}
