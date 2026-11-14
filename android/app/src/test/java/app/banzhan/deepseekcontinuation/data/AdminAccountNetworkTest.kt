package app.banzhan.deepseekcontinuation.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AdminAccountNetworkTest {
    private val config = """{"public_enabled":true,"daily_limit":50,"user_daily_limit":5,"model":"test-model","base_url":"https://provider.example/v1","api_key_configured":true,"context_tokens":8192,"max_output_tokens":1024,"provider_timeout_seconds":120,"api_enabled":true,"mock_mode":false,"cost_known":false,"config_version":7}"""
    private val user = """{"id":"registered-user","username":"小明_123","registration_kind":"invitation","daily_limit_override":null,"access_mode":"public","created_at":"2026-11-14T12:00:00+08:00","last_seen_at":null,"daily_limit":5,"daily_used":1,"daily_reserved":1,"daily_remaining":3,"total_requests":2,"completed":1,"failed":0,"uncertain":0,"cancelled":0,"input_tokens":200,"output_tokens":30}"""
    private fun users(value: String = user) = """{"limit":50,"offset":0,"total":1,"day":"2026-11-14","reset_at":"2026-11-15T00:00:00+08:00","users":[$value]}"""

    @Test fun publicRegistrationCapAndCountAreSeparateFromDailyQuotaAndReadOnlyCount() {
        val older = AdminNetwork.parseAdminConfig(config)
        assertEquals(20, older.publicRegistrationLimit)
        assertEquals(0, older.publicRegisteredCount)
        // Lowering a cap below the already registered count must not discard users.
        val parsed = AdminNetwork.parseAdminConfig(config.dropLast(1) + ",\"public_registration_limit\":10,\"public_registered_count\":20}")
        assertEquals(10, parsed.publicRegistrationLimit)
        assertEquals(20, parsed.publicRegisteredCount)
        assertEquals(50, parsed.dailyLimit)
        assertEquals(5, parsed.userDailyLimit)
        val patch = json(AdminNetwork.adminPatchPayload(parsed.copy(publicRegistrationLimit = 30), null))
        assertEquals(30, patch["public_registration_limit"]!!.jsonPrimitive.int)
        assertFalse(patch.containsKey("public_registered_count"))
        assertFalse(patch.containsKey("config_version"))
    }

    @Test fun malformedRegistrationCountersCannotBecomeAnUnlimitedCap() {
        listOf("-1", "1000001", "null", "\"20\"").forEach {
            assertThrows(Exception::class.java) {
                AdminNetwork.parseAdminConfig(config.dropLast(1) + ",\"public_registration_limit\":$it}")
            }
        }
        assertThrows(Exception::class.java) {
            AdminNetwork.parseAdminConfig(config.dropLast(1) + ",\"public_registered_count\":-1}")
        }
    }

    @Test fun registeredUsersPreserveSourceNullableOverrideAndServerDay() {
        val page = AdminNetwork.parseAdminUsers(users())
        assertEquals("2026-11-14", page.day)
        assertEquals("2026-11-15T00:00:00+08:00", page.resetAt)
        val parsed = page.users.single()
        assertEquals("小明_123", parsed.username)
        assertEquals("invitation", parsed.registrationKind)
        assertNull(parsed.dailyLimitOverride)
        assertEquals(3, parsed.dailyRemaining)
        val override = AdminNetwork.parseAdminUsers(users(user.replace("\"daily_limit_override\":null", "\"daily_limit_override\":12"))).users.single()
        assertEquals(12, override.dailyLimitOverride)
        val oldUser = user.replace("\"username\":\"小明_123\",\"registration_kind\":\"invitation\",\"daily_limit_override\":null,", "")
        val old = AdminNetwork.parseAdminUsers(users(oldUser)).users.single()
        assertNull(old.username)
        assertNull(old.registrationKind)
        assertNull(old.dailyLimitOverride)
    }

    @Test fun invalidUserQuotaMetadataAndDatesAreRejected() {
        listOf(
            users(user.replace("\"invitation\"", "\"unknown\"")),
            users(user.replace("\"daily_limit_override\":null", "\"daily_limit_override\":-1")),
            users(user.replace("\"daily_limit_override\":null", "\"daily_limit_override\":\"5\"")),
            users().replace("\"day\":\"2026-11-14\"", "\"day\":\"2026-02-30\""),
            users().replace("2026-11-15T00:00:00+08:00", "2026-11-15T00:00:00"),
        ).forEach { assertThrows(Exception::class.java) { AdminNetwork.parseAdminUsers(it) } }
    }

    @Test fun quotaPatchDistinguishesFollowingDefaultFromSpecificDailyAndTodayAmounts() {
        val defaults = json(AdminNetwork.userQuotaPayload(null, null, null))
        assertEquals(JsonNull, defaults["daily_limit"])
        assertFalse(defaults.containsKey("today_remaining"))
        assertFalse(defaults.containsKey("day"))
        val patch = json(AdminNetwork.userQuotaPayload(12, 0, "2026-11-14"))
        assertEquals(12, patch["daily_limit"]!!.jsonPrimitive.int)
        assertEquals(0, patch["today_remaining"]!!.jsonPrimitive.int)
        assertEquals("2026-11-14", patch["day"]!!.jsonPrimitive.content)
        assertFalse(patch.containsKey("daily_used"))
        assertFalse(patch.containsKey("daily_reserved"))
        // A day is a concurrency guard for today's adjustment, not a historical quota mutation.
        assertFalse(json(AdminNetwork.userQuotaPayload(5, null, "2026-11-14")).containsKey("day"))
        assertThrows(Exception::class.java) { AdminNetwork.userQuotaPayload(-1, null, null) }
        assertThrows(Exception::class.java) { AdminNetwork.userQuotaPayload(null, 1_000_001, "2026-11-14") }
        assertThrows(Exception::class.java) { AdminNetwork.userQuotaPayload(5, 1, "2026-02-30") }
    }

    @Test fun invitationGetPreservesUnusedCodeAndUsedMetadataWhilePostCanBeMinimal() {
        val payload = """{"invitations":[{"id":"unused","code":"invite_one","expires_at":"2026-12-14T12:00:00+08:00","created_at":"2026-11-14T12:00:00+08:00","redeemed_at":null,"redeemed_by":null},{"id":"used","code":null,"expires_at":"2026-12-14T12:00:00+08:00","created_at":"2026-11-14T12:00:00+08:00","redeemed_at":"2026-11-14T12:05:00+08:00","redeemed_by":"registered-user"}]}"""
        val invites = AdminNetwork.parseRegistrationInvites(payload)
        assertEquals("invite_one", invites[0].code)
        assertNull(invites[0].usedAt)
        assertNull(invites[0].usedBy)
        assertNull(invites[1].code)
        assertEquals("2026-11-14T12:05:00+08:00", invites[1].usedAt)
        assertEquals("registered-user", invites[1].usedBy)
        val created = AdminNetwork.parseRegistrationInvites("""{"invitations":[{"id":"new","code":"invite_new","expires_at":"2026-12-14T12:00:00+08:00"}]}""").single()
        assertEquals("invite_new", created.code)
        assertEquals("", created.createdAt)
        assertNull(created.usedAt)
        assertThrows(Exception::class.java) { AdminNetwork.parseRegistrationInvites(payload.replace("\"invite_one\"", "\"\"")) }
        assertThrows(Exception::class.java) { AdminNetwork.parseRegistrationInvites(payload.replace("2026-11-14T12:05:00+08:00", "bad timestamp")) }
    }

    @Test fun invitationListFetchesAllPagesWithinTheBackendLimit() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val invitations = AdminNetwork.collectRegistrationInvites { limit, offset ->
            requests += limit to offset
            check(requests.size <= 3) { "Unexpected extra page" }
            invitationPage(total = 205, offset = offset, count = minOf(limit, 205 - offset))
        }
        assertEquals(listOf(100 to 0, 100 to 100, 100 to 200), requests)
        assertEquals(205, invitations.size)
        assertEquals(205, invitations.map { it.id }.toSet().size)
        assertEquals("invite-204", invitations.last().id)
        assertEquals("code-204", invitations.last().code)
        val empty = AdminNetwork.collectRegistrationInvites { limit, offset ->
            assertEquals(100, limit)
            assertEquals(0, offset)
            invitationPage(total = 0, offset = 0, count = 0)
        }
        assertTrue(empty.isEmpty())
    }

    @Test fun invitationListRejectsShortChangedDuplicateOrUnsupportedPages() = runBlocking {
        val invalidSecondPages = listOf(
            invitationPage(total = 101, offset = 100, count = 0),
            invitationPage(total = 102, offset = 100, count = 2),
            invitationPage(total = 101, offset = 0, count = 1),
            invitationPage(total = 101, offset = 100, count = 1, firstId = 0),
            invitationPage(total = 101, offset = 100, count = 1).replace("\"code-100\"", "null"),
            invitationPage(total = 101, offset = 100, count = 1).replace("\"limit\":100", "\"limit\":200"),
        )
        invalidSecondPages.forEach { invalid ->
            var requests = 0
            val failure = runCatching {
                AdminNetwork.collectRegistrationInvites { _, offset ->
                    check(++requests <= 2) { "Pagination must stop on an invalid page" }
                    if (offset == 0) invitationPage(total = 101, offset = 0, count = 100) else invalid
                }
            }.exceptionOrNull()
            assertTrue(failure is SummaryFailure)
            assertTrue(failure!!.message.orEmpty().contains("邀请码"))
            assertEquals(2, requests)
        }
    }

    private fun invitationPage(total: Int, offset: Int, count: Int, firstId: Int = offset): String {
        val values = (firstId until firstId + count).joinToString(",") { id ->
            """{"id":"invite-$id","code":"code-$id","expires_at":"2026-12-14T12:00:00+08:00"}"""
        }
        return """{"limit":100,"offset":$offset,"total":$total,"invitations":[$values]}"""
    }

    private fun json(payload: String) = appJson.parseToJsonElement(payload) as JsonObject
}
