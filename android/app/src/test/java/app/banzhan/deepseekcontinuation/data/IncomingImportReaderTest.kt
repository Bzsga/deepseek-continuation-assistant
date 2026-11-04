package app.banzhan.deepseekcontinuation.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingImportReaderTest {
    @Test
    fun readsActualBytesWithoutTrustingAvailableEstimate() {
        val expected = "{\"messages\":[]}".toByteArray()
        val input = object : ByteArrayInputStream(expected) {
            override fun available(): Int = Int.MAX_VALUE
        }
        assertArrayEquals(expected, readIncomingImportBytes(input))
    }

    @Test
    fun rejectsAStreamAtThe32MiBBoundaryWithoutReadingTheRest() {
        var requestedBytes = 0L
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                requestedBytes += length
                return length
            }
        }
        val error = try {
            readIncomingImportBytes(input)
            throw AssertionError("Expected a bounded read failure")
        } catch (expected: IncomingImportReadException) {
            expected
        }
        assertEquals(32 * 1024 * 1024, IncomingImportPolicy.MAX_BYTES)
        assertTrue(requestedBytes > IncomingImportPolicy.MAX_BYTES)
        assertTrue(requestedBytes <= IncomingImportPolicy.MAX_BYTES + 32 * 1024)
        assertEquals("导入文件超过 32 MiB，请拆分后导入。", error.message)
    }

    @Test(expected = IncomingImportReadException::class)
    fun aBrokenZeroByteStreamCannotSpinForever() {
        val input = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        readIncomingImportBytes(input)
    }
}
