package app.banzhan.deepseekcontinuation.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated pixels only. No device gallery images or network requests are used. */
@RunWith(AndroidJUnit4::class)
class FeedbackImageTest {
    @Test fun resamplingRotatesAndRemovesMetadataWhileBoundingUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(2561, 1600, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("feedback-synthetic-", ".jpg", context.cacheDir)
        try {
            Canvas(source).apply {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, 1280f, 1600f, Paint().apply { color = Color.BLUE })
            }
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_ARTIST, "SYNTHETIC_METADATA_MUST_BE_REMOVED")
                saveAttributes()
            }
            val prepared = FeedbackImageProcessor.prepare(file.readBytes())
            assertTrue(prepared.jpegBytes.size <= 1024 * 1024)
            assertTrue(prepared.width <= 1280 && prepared.height <= 1280)
            assertTrue(prepared.height > prepared.width)
            val decoded = BitmapFactory.decodeByteArray(prepared.jpegBytes, 0, prepared.jpegBytes.size)
            assertEquals(prepared.width, decoded.width)
            assertEquals(prepared.height, decoded.height)
            decoded.recycle()
            val metadata = ExifInterface(ByteArrayInputStream(prepared.jpegBytes))
            assertNull(metadata.getAttribute(ExifInterface.TAG_ARTIST))
            assertTrue(metadata.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in
                listOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        } finally {
            source.recycle()
            file.delete()
        }
    }
}
