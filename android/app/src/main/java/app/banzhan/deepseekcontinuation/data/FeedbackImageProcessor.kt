package app.banzhan.deepseekcontinuation.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object FeedbackImageProcessor {
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 1024 * 1024
    private const val MAX_SIDE = 1280

    suspend fun prepare(input: ByteArray): FeedbackDraftImage = withContext(Dispatchers.IO) {
        require(input.isNotEmpty() && input.size <= MAX_INPUT_BYTES) { "请选择不超过20MiB的图片。" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && maxOf(bounds.outWidth, bounds.outHeight) <= 50_000 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "图片无法读取或尺寸过大，请换一张图片。" }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options)
            ?: error("图片无法解码，请换一张图片。")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(input)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        var oriented: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            val orientedImage = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            oriented = orientedImage
            val ratio = minOf(1.0, MAX_SIDE.toDouble() / maxOf(orientedImage.width, orientedImage.height))
            val width = (orientedImage.width * ratio).toInt().coerceAtLeast(1)
            val height = (orientedImage.height * ratio).toInt().coerceAtLeast(1)
            var working = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            flattened = working
            Canvas(working).apply {
                drawColor(Color.WHITE)
                drawBitmap(orientedImage, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            var bytes = encode(working)
            while (bytes.size > MAX_IMAGE_BYTES && maxOf(working.width, working.height) > 320) {
                val previous = working
                working = Bitmap.createScaledBitmap(previous,
                    (previous.width * 0.85).toInt().coerceAtLeast(1),
                    (previous.height * 0.85).toInt().coerceAtLeast(1), true)
                flattened = working
                if (previous !== working) previous.recycle()
                bytes = encode(working)
            }
            require(bytes.size <= MAX_IMAGE_BYTES) { "图片压缩后仍超过1MiB，请换一张图片。" }
            // Re-encoding pixels produces a new JPEG without source EXIF, location or other metadata.
            FeedbackDraftImage(UUID.randomUUID().toString(), bytes, working.width, working.height)
        } finally {
            flattened?.recycle()
            if (oriented !== decoded) oriented?.recycle()
            decoded.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)) { "图片压缩失败，请重新选择。" }
        stream.toByteArray()
    }
}
