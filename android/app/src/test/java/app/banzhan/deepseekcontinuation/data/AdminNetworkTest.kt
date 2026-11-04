package app.banzhan.deepseekcontinuation.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminNetworkTest {
    @Test
    fun loginParsesScopedSessionAndTimezoneAwareExpiry() {
        val prefs = AdminNetwork.parseDeveloperLogin("https://example.com/service/", """{"token":"test-session-token","expires_at":"2026-11-04T00:00:00+08:00"}""")
        assertEquals("https://example.com/service", prefs.serviceUrl)
        assertEquals("test-session-token", prefs.sessionToken)
        assertEquals("2026-11-04T00:00:00+08:00", prefs.expiresAt)
        assertThrows(Exception::class.java) {
            AdminNetwork.parseDeveloperLogin("https://example.com", """{"token":"invalid token","expires_at":"2026-11-04T00:00:00+08:00"}""")
        }
        assertThrows(Exception::class.java) {
            AdminNetwork.parseDeveloperLogin("https://example.com", """{"token":"test-session-token","expires_at":"2026-11-04T00:00:00"}""")
        }
    }

    @Test
    fun adminConfigPreservesCountersAndReadOnlyFlags() {
        val config = AdminNetwork.parseAdminConfig(configPayload())
        assertTrue(config.publicEnabled)
        assertEquals(50, config.dailyLimit)
        assertEquals(3, config.userDailyLimit)
        assertTrue(config.apiKeyConfigured)
        assertEquals(120.0, config.providerTimeoutSeconds, 0.0)
        assertEquals(7, config.configVersion)
    }

    @Test
    fun secretPatchDistinguishesPreserveClearAndReplace() {
        val config = AdminNetwork.parseAdminConfig(configPayload())
        val preserve = json(AdminNetwork.adminPatchPayload(config, null))
        assertFalse(preserve.containsKey("api_key"))
        assertFalse(preserve.containsKey("api_key_configured"))
        assertFalse(preserve.containsKey("config_version"))
        assertFalse(preserve.containsKey("api_enabled"))
        assertEquals("", json(AdminNetwork.adminPatchPayload(config, ""))["api_key"]!!.jsonPrimitive.content)
        assertEquals("test-upstream-key", json(AdminNetwork.adminPatchPayload(config, "test-upstream-key"))["api_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedAdminValuesAndUnsafeProviderSettingsAreRejected() {
        listOf(
            configPayload().replace("\"daily_limit\":50", "\"daily_limit\":-1"),
            configPayload().replace("\"daily_limit\":50", "\"daily_limit\":1.5"),
            configPayload().replace("\"public_enabled\":true", "\"public_enabled\":\"true\""),
            configPayload().replace("\"context_tokens\":8192", "\"context_tokens\":512"),
            configPayload().replace("\"provider_timeout_seconds\":120", "\"provider_timeout_seconds\":1e999"),
            configPayload().replace("https://provider.example/v1", "http://provider.example/v1")
        ).forEach { payload -> assertThrows(Exception::class.java) { AdminNetwork.parseAdminConfig(payload) } }
        val config = AdminNetwork.parseAdminConfig(configPayload())
        assertThrows(IllegalArgumentException::class.java) { AdminNetwork.adminPatchPayload(config, "x".repeat(4097)) }
    }

    @Test
    fun serverErrorsRedactLongSecretsBeforeDisplayTruncation() {
        val upstream = "test-upstream-" + "x".repeat(700)
        val token = "test-admin-session"
        val detail = "upstream=$upstream token=$token"
        val safe = SummaryNetwork.redactSecrets(detail, listOf(token, upstream)).take(500)
        assertFalse(safe.contains("test-upstream"))
        assertFalse(safe.contains(token))
        assertTrue(safe.contains("[已隐藏]"))
        assertEquals("[已隐藏] [已隐藏]", SummaryNetwork.redactSecrets("test-key-long test-key", listOf("test-key", "test-key-long")))
    }

    @Test
    fun statisticsKeepLongTokenCountsAndDailyReservations() {
        val stats = AdminNetwork.parseAdminStats(statsPayload())
        assertEquals(3_000_000_000L, stats.totals.inputTokens)
        assertEquals(1, stats.today.dailyReserved)
        assertEquals(7, stats.today.dailyRemaining)
        assertEquals("2026-11-03", stats.daily.single().date)
        assertNull(stats.totals.usageKnownRequests)
        assertNull(stats.totals.usageUnknownRequests)
        assertNull(stats.daily.single().usageKnownRequests)
        assertNull(stats.daily.single().usageUnknownRequests)
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminStats(statsPayload().replace("\"input_tokens\":3000000000", "\"input_tokens\":-1"))
        }
    }

    @Test
    fun paginatedUsersParseNullableLastSeenAndRejectInvalidCounts() {
        val page = AdminNetwork.parseAdminUsers(usersPayload())
        assertEquals(1L, page.total)
        val user = page.users.single()
        assertEquals("public", user.accessMode)
        assertNull(user.lastSeenAt)
        assertEquals(3_000_000_000L, user.inputTokens)
        assertNull(user.usageKnownRequests)
        assertNull(user.usageUnknownRequests)
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminUsers(usersPayload().replace("\"daily_used\":1", "\"daily_used\":-1"))
        }
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminUsers(usersPayload().replace("\"limit\":50", "\"limit\":0"))
        }
    }

    @Test
    fun reportedAndUnknownUsageCountsRemainDistinctIncludingExplicitZero() {
        val statsJson = statsPayload().replace("\"requests\":3,", "\"requests\":3,\"usage_known_requests\":2,\"usage_unknown_requests\":1,")
        val stats = AdminNetwork.parseAdminStats(statsJson)
        assertEquals(2L, stats.totals.usageKnownRequests)
        assertEquals(1L, stats.totals.usageUnknownRequests)
        assertEquals(2L, stats.daily.single().usageKnownRequests)
        assertEquals(1L, stats.daily.single().usageUnknownRequests)
        val usersJson = usersPayload().replace("\"total_requests\":1,", "\"total_requests\":1,\"usage_known_requests\":1,\"usage_unknown_requests\":0,")
        val user = AdminNetwork.parseAdminUsers(usersJson).users.single()
        assertEquals(1L, user.usageKnownRequests)
        assertEquals(0L, user.usageUnknownRequests)
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminStats(statsJson.replace("\"usage_unknown_requests\":1", "\"usage_unknown_requests\":-1"))
        }
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminUsers(usersJson.replace("\"usage_known_requests\":1", "\"usage_known_requests\":\"1\""))
        }
    }

    private fun json(value: String): JsonObject = SummaryProtocol.json.parseToJsonElement(value) as JsonObject

    private fun statsPayload(): String = """
        {"timezone":"Asia/Shanghai","days":1,"start_date":"2026-11-03","end_date":"2026-11-03","generated_at":"2026-11-03T12:00:00+08:00",
        "totals":{"users":2,"new_users":1,"active_users":1,"requests":3,"input_tokens":3000000000,"output_tokens":10,"completed":1,"failed":1,"uncertain":0,"cancelled":0,"queued":1,"running":0},
        "today":{"daily_limit":10,"daily_used":2,"daily_reserved":1,"daily_remaining":7,"reset_at":"2026-11-04T00:00:00+08:00"},
        "daily":[{"date":"2026-11-03","new_users":1,"active_users":1,"requests":3,"input_tokens":3000000000,"output_tokens":10,"completed":1,"failed":1,"uncertain":0,"cancelled":0}]}
    """.trimIndent()

    private fun usersPayload(): String = """
        {"limit":50,"offset":0,"total":1,"users":[{"id":"user-id","access_mode":"public","created_at":"2026-11-03T12:00:00+08:00","last_seen_at":null,
        "daily_limit":3,"daily_used":1,"daily_reserved":0,"daily_remaining":2,"total_requests":1,"completed":1,"failed":0,"uncertain":0,"cancelled":0,"input_tokens":3000000000,"output_tokens":10}]}
    """.trimIndent()

    private fun configPayload(): String =
        """{"public_enabled":true,"daily_limit":50,"user_daily_limit":3,"model":"test-model","base_url":"https://provider.example/v1","api_key_configured":true,"context_tokens":8192,"max_output_tokens":1024,"provider_timeout_seconds":120,"api_enabled":true,"mock_mode":false,"cost_known":false,"config_version":7}"""
}
