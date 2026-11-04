package app.banzhan.deepseekcontinuation.core

import java.io.Reader

/** Bounds the live JSON text to one top-level array item; the JSON parser validates each item. */
internal class JsonDocumentReader(reader: Reader) {
    private val input = reader.buffered()
    private var next = input.read()

    fun read(consume: (text: String, arrayItem: Boolean) -> Unit) {
        if (next == 0xfeff) advance()
        whitespace()
        require(next >= 0) { "JSON 文件为空。" }
        if (next == '['.code) {
            advance()
            whitespace()
            if (next != ']'.code) {
                while (true) {
                    require(next >= 0 && next != ']'.code) { "JSON 数组不完整或包含尾随逗号。" }
                    consume(value(1), true)
                    whitespace()
                    if (next == ']'.code) break
                    require(next == ','.code) { "JSON 数组缺少逗号或结束标记。" }
                    advance()
                    whitespace()
                }
            }
            advance()
        } else {
            consume(value(0), false)
        }
        whitespace()
        require(next == -1) { "JSON 根内容之后存在多余数据。" }
    }

    private fun value(outerDepth: Int): String {
        val text = StringBuilder()
        val containers = ArrayDeque<Char>()
        var quoted = false
        var escaped = false
        var started = false
        while (next >= 0) {
            val character = next.toChar()
            if (started && !quoted && containers.isEmpty()) break
            if (!started && character !in charArrayOf('{', '[', '"')) {
                // Scalars still go through the real JSON parser, which reports invalid values.
                while (next >= 0 && next.toChar() !in charArrayOf(' ', '\t', '\r', '\n', ',', ']')) {
                    text.append(next.toChar())
                    advance()
                }
                require(text.isNotEmpty()) { "JSON 数组包含空项。" }
                return text.toString()
            }
            started = true
            text.append(character)
            advance()
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> {
                    containers.addLast(character)
                    require(containers.size + outerDepth <= 128) { "JSON 嵌套超过 128 层。" }
                }
                '}', ']' -> {
                    val expected = if (character == '}') '{' else '['
                    require(containers.removeLastOrNull() == expected) { "JSON 结束标记不匹配。" }
                }
            }
        }
        require(started && !quoted && containers.isEmpty()) { "JSON 内容不完整。" }
        return text.toString()
    }

    private fun advance() { next = input.read() }

    private fun whitespace() {
        while (next == ' '.code || next == '\t'.code || next == '\r'.code || next == '\n'.code) advance()
    }
}
