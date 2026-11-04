package app.banzhan.deepseekcontinuation.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadPartsTest {
    @Test
    fun emptyAndExactThresholdStayOnePart() {
        assertEquals(listOf(""), PayloadSegments.split(""))
        val exact = "a".repeat(PayloadSegments.CHARACTERS)
        assertEquals(listOf(exact), PayloadSegments.split(exact))
        val above = exact + "b"
        assertEquals(listOf(exact, "b"), PayloadSegments.split(above))
        assertEquals(above, join(PayloadSegments.split(above)))
    }

    @Test
    fun supplementaryCharacterAcrossBoundaryRoundTripsWithoutBrokenSurrogates() {
        val value = "x".repeat(PayloadSegments.CHARACTERS - 1) + "😀" + "尾部"
        val parts = PayloadSegments.split(value)
        assertEquals(PayloadSegments.CHARACTERS - 1, parts.first().length)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.length <= PayloadSegments.CHARACTERS })
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it })
        assertEquals(value, join(parts))
    }

    @Test
    fun repeatedSupplementaryAndBmpCharactersRemainBounded() {
        val value = "😀".repeat(PayloadSegments.CHARACTERS) + "汉".repeat(PayloadSegments.CHARACTERS + 1)
        val parts = PayloadSegments.split(value)
        assertTrue(parts.size > 2)
        assertTrue(parts.all { it.length <= PayloadSegments.CHARACTERS })
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 4 * PayloadSegments.CHARACTERS })
        assertEquals(value, join(parts))
    }

    @Test
    fun plainValuesAndValidMarkersAreDistinguished() {
        assertNull(PayloadSegments.count("{\"payload\":\"text\"}"))
        assertEquals(2, PayloadSegments.count(PayloadSegments.PREFIX + "2"))
        assertEquals("inline", PayloadSegments.join("inline", emptyList()))
        listOf("", "0", "-1", "65537", "two", "2147483648").forEach { count ->
            assertThrows(IllegalArgumentException::class.java) { PayloadSegments.count(PayloadSegments.PREFIX + count) }
        }
    }

    @Test
    fun missingDuplicateExtraAndUnorderedPartsAreRejected() {
        val marker = PayloadSegments.PREFIX + "2"
        val first = PayloadPart("tasks", "task-1", 0, "first")
        val second = PayloadPart("tasks", "task-1", 1, "second")
        listOf(
            listOf(first),
            listOf(first, first),
            listOf(second, first),
            listOf(first, second, second.copy(part = 2)),
            listOf(first, second.copy(part = 2))
        ).forEach { parts ->
            assertThrows(IllegalArgumentException::class.java) { PayloadSegments.join(marker, parts) }
        }
        assertEquals("firstsecond", PayloadSegments.join(marker, listOf(first, second)))
    }

    private fun join(parts: List<String>): String = PayloadSegments.join(
        PayloadSegments.PREFIX + parts.size,
        parts.mapIndexed { index, content -> PayloadPart("conversations", "conversation-1", index, content) }
    )
}
