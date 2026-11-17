package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekShareLinkTest {
    @Test fun acceptsSharedTextAndCanonicalizesOnlyTheValidatedPublicLink() {
        val expected = DeepSeekShareLink("abc123_def-456", "https://chat.deepseek.com/share/abc123_def-456")
        assertEquals(expected, DeepSeekShareLink.parse("我和 DeepSeek 的对话：\nhttps://chat.deepseek.com/share/abc123_def-456/?from=timeline。"))
        assertEquals(expected, DeepSeekShareLink.parse("（https://CHAT.DEEPSEEK.COM:443/share/abc123_def-456#section）"))
        assertEquals(expected, DeepSeekShareLink.parse("${expected.url}\n${expected.url}?from=copy"))
    }

    @Test fun rejectsOtherOriginsCredentialsUnsafeIdsAndAmbiguousTargets() {
        listOf("", "abc123", "https://example.com/share/abc123",
            "https://chat.deepseek.com.evil.example/share/abc123", "http://chat.deepseek.com/share/abc123",
            "https://chat.deepseek.com@evil.example/share/abc123", "https://user@chat.deepseek.com/share/abc123",
            "https://chat.deepseek.com:8443/share/abc123", "https://chat.deepseek.com/share/abc123/extra",
            "https://chat.deepseek.com/share/abc%31%32%33", "https://chat.deepseek.com/share/../abc123",
            "https://chat.deepseek.com/share/abc123 https://chat.deepseek.com/share/def456",
        ).forEach { value ->
            try { DeepSeekShareLink.parse(value); throw AssertionError("Accepted invalid link") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
