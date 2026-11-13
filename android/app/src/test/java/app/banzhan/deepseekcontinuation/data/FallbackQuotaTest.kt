package app.banzhan.deepseekcontinuation.data

import org.junit.Assert.*
import org.junit.Test

class FallbackQuotaTest {
    private val payload = """{"enabled":true,"access_mode":"public","model":"server-model","remaining_cny":0,"per_job_limit_cny":0,"daily_limit":50,"daily_used":0,"daily_remaining":50,"daily_reserved":0,"user_daily_limit":5,"user_daily_used":0,"user_daily_remaining":5,"user_daily_reserved":0,"reset_at":"2026-11-14T00:00:00+08:00","context_tokens":64000,"max_output_tokens":4096,"organization_protocol_version":8}"""

    private fun quota(extra: String = "") = SummaryNetwork.parseQuota(payload.dropLast(1) + extra + "}")

    @Test fun oldServersKeepOneRequestPerChunk() {
        val old = quota()
        assertFalse(old.automaticFallbackEnabled)
        assertEquals(1, old.fallbackAttemptLimit)
        assertEquals("2", old.requestCountText(2))
        assertEquals(2L, old.requestUpperBound(2))
        assertNull(old.organizationBlocker())
    }

    @Test fun advertisedFallbackShowsRangeAndConservativeQuotaUpperBound() {
        val current = quota(",\"automatic_fallback_enabled\":true,\"max_attempts_per_chunk\":3")
        assertTrue(current.automaticFallbackEnabled)
        assertEquals(3, current.fallbackAttemptLimit)
        assertEquals("2～6", current.requestCountText(2))
        assertEquals(6L, current.requestUpperBound(2))
        assertTrue(automaticFallbackNotice(current.fallbackAttemptLimit).contains("每段最多 3 次"))
        assertTrue(automaticFallbackNotice(current.fallbackAttemptLimit).contains("按实际发出的请求扣次"))
    }

    @Test fun malformedOptionalCapabilitiesDoNotDisableOrdinaryOrganization() {
        listOf("null", "false", "\"true\"", "1", "{}", "[]").forEach { value ->
            val parsed = quota(",\"automatic_fallback_enabled\":$value,\"max_attempts_per_chunk\":3")
            assertEquals(1, parsed.fallbackAttemptLimit)
            assertNull(parsed.organizationBlocker())
        }
        listOf("null", "0", "4", "-1", "1.5", "\"3\"", "true", "2147483648", "{}").forEach { value ->
            val parsed = quota(",\"automatic_fallback_enabled\":true,\"max_attempts_per_chunk\":$value")
            assertEquals(1, parsed.fallbackAttemptLimit)
            assertNull(parsed.organizationBlocker())
        }
    }

    @Test fun fallbackDoesNotChangeCapturedTaskBudgetOrModel() {
        val settings = ServiceSettings(mode = "companion", model = "server-model", contextTokens = 64000,
            maxOutputTokens = 4096)
        val legacy = settingsWithServerBudget(settings, quota())
        val fallback = settingsWithServerBudget(settings,
            quota(",\"automatic_fallback_enabled\":true,\"max_attempts_per_chunk\":3,\"future_route_field\":{}"))
        assertEquals(legacy, fallback)
        assertEquals("server-model", fallback.model)
    }

    @Test fun monetaryModeAndDisabledFallbackKeepSingleRequestEstimate() {
        val current = quota(",\"automatic_fallback_enabled\":true,\"max_attempts_per_chunk\":3")
        assertEquals("2", current.copy(accessMode = "invitation").requestCountText(2))
        assertEquals("2", current.copy(automaticFallbackEnabled = false).requestCountText(2))
        assertEquals("2", (null as CompanionQuota?).requestCountText(2))
    }

    @Test fun requestRangeIsBoundedAndCannotOverflow() {
        assertEquals("0", companionRequestCountText(0, 3))
        assertEquals("0", companionRequestCountText(-1, 3))
        assertEquals("2～6", companionRequestCountText(2, 99))
        assertEquals("2147483647～6442450941", companionRequestCountText(Int.MAX_VALUE, 3))
    }
}
