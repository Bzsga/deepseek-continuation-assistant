package app.banzhan.deepseekcontinuation.data

/** Room's cursor reads bounded cells, even when the logical JSON or original export is large. */
internal object PayloadSegments {
    const val CHARACTERS = 65_536
    const val PREFIX = "@segments-v2:"

    fun split(value: String): List<String> {
        if (value.length <= CHARACTERS) return listOf(value)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + CHARACTERS, value.length)
            if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            parts += value.substring(start, end)
            start = end
        }
        return parts
    }

    fun count(marker: String): Int? {
        if (!marker.startsWith(PREFIX)) return null
        val count = marker.removePrefix(PREFIX).toIntOrNull()
        require(count != null && count in 1..65_536) { "本地大文本分段标记损坏，请恢复备份" }
        return count
    }

    fun join(marker: String, parts: List<PayloadPart>): String {
        val count = count(marker) ?: return marker
        require(parts.size == count && parts.withIndex().all { (index, part) -> part.part == index }) {
            "本地大文本分段不完整，请恢复备份"
        }
        return parts.joinToString("") { it.content }
    }
}
