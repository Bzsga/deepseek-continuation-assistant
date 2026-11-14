package app.banzhan.deepseekcontinuation.data

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AccountNetworkTest {
    private val user = """{"id":"account-1","username":"小明_123","registration_kind":"public","created_at":"2026-11-14T12:00:00+08:00"}"""
    private val quota = """{"enabled":true,"access_mode":"public","model":"server-model","daily_limit":50,"daily_used":0,"daily_remaining":50,"daily_reserved":0,"user_daily_limit":5,"user_daily_used":0,"user_daily_remaining":5,"user_daily_reserved":0,"reset_at":"2026-11-15T00:00:00+08:00","context_tokens":64000,"max_output_tokens":4096,"organization_protocol_version":12}"""
    private fun authentication() = """{"token":"synthetic-account-token","expires_at":"2026-12-14T12:00:00+08:00","user":$user,"quota":$quota}"""

    @Test fun authenticationBindsSessionToServiceAndPreservesPersonalQuota() {
        val parsed = AccountNetwork.parseAuthentication("https://example.test/continuation/", authentication())
        assertEquals("https://example.test/continuation", parsed.session.serviceUrl)
        assertEquals("account-1", parsed.session.user.id)
        assertEquals("小明_123", parsed.session.user.username)
        assertEquals("public", parsed.session.user.registrationKind)
        assertEquals("synthetic-account-token", parsed.session.token)
        assertEquals(5, parsed.quota.userDailyRemaining)
        assertFalse(parsed.session.toString().contains("synthetic-account-token"))
        assertFalse(parsed.toString().contains("synthetic-account-token"))
    }

    @Test fun profileContainsOnlyUserAndQuotaWithInvitationRegistration() {
        val parsed = AccountNetwork.parseProfile("""{"user":${user.replace("public", "invitation")},"quota":$quota}""")
        assertEquals("invitation", parsed.user.registrationKind)
        assertEquals(5, parsed.quota.userDailyLimit)
        assertEquals(12, parsed.quota.organizationProtocolVersion)
    }

    @Test fun registrationNormalizesUsernameAndKeepsPasswordVerbatim() {
        val parsed = json(AccountNetwork.registrationPayload("  Ａｌｉｃｅ_小明  ", "  secret  ", "  invite_X-123  "))
        assertEquals("Alice_小明", parsed["username"]!!.jsonPrimitive.content)
        assertEquals("  secret  ", parsed["password"]!!.jsonPrimitive.content)
        assertEquals("invite_X-123", parsed["invitation_code"]!!.jsonPrimitive.content)
        val blank = json(AccountNetwork.registrationPayload("小明_123", "password123", " \u0085 "))
        assertFalse(blank.containsKey("invitation_code"))
        assertFalse(json(AccountNetwork.loginPayload("小明_123", "password123")).containsKey("invitation_code"))
    }

    @Test fun usernameAndPasswordLengthsUseUnicodeCharacters() {
        val rareLetter = "\uD840\uDC00"
        assertEquals(rareLetter.repeat(3), AccountNetwork.normalizedUsername(rareLetter.repeat(3)))
        assertEquals(rareLetter.repeat(32), AccountNetwork.normalizedUsername(rareLetter.repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { AccountNetwork.normalizedUsername(rareLetter.repeat(33)) }
        assertEquals("User_123", AccountNetwork.normalizedUsername("\u0085User_123\u0085"))
        val password = "\uD83D\uDE42".repeat(8)
        assertEquals(password, json(AccountNetwork.loginPayload("User_123", password))["password"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { AccountNetwork.loginPayload("User_123", "\uD83D\uDE42".repeat(7)) }
        assertThrows(IllegalArgumentException::class.java) { AccountNetwork.loginPayload("User_123", "\uD83D\uDE42".repeat(129)) }
    }

    @Test fun invalidCredentialsAndInvitationCannotBeEncodedSilently() {
        listOf("ab", "user name", "user/name", "user@name", "user\u0000name", "🙂🙂🙂").forEach {
            assertThrows(IllegalArgumentException::class.java) { AccountNetwork.loginPayload(it, "password123") }
        }
        listOf("short", "x".repeat(129), "password\uD800", "password\uDC00").forEach {
            assertThrows(IllegalArgumentException::class.java) { AccountNetwork.loginPayload("user123", it) }
        }
        listOf("invite/code", "invite code", "x".repeat(257)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AccountNetwork.registrationPayload("user123", "password123", it) }
        }
    }

    @Test fun malformedAuthenticationIsRejectedWithoutAcceptingPartialIdentity() {
        listOf(
            authentication().replace("\"synthetic-account-token\"", "\"bad token\""),
            authentication().replace("\"synthetic-account-token\"", "123"),
            authentication().replace("2026-12-14T12:00:00+08:00", "2026-12-14T12:00:00"),
            authentication().replace("2026-11-14T12:00:00+08:00", "not-a-date"),
            authentication().replace("\"registration_kind\":\"public\"", "\"registration_kind\":\"legacy\""),
            authentication().replace("\"id\":\"account-1\"", "\"id\":\"\""),
            authentication().replace("\"username\":\"小明_123\"", "\"username\":\"bad name\""),
            authentication().replace("\"user_daily_remaining\":5", "\"user_daily_remaining\":-1"),
            """{"token":"synthetic-account-token","expires_at":"2026-12-14T12:00:00+08:00","user":$user}""",
        ).forEach { payload ->
            assertThrows(Exception::class.java) { AccountNetwork.parseAuthentication("https://example.test", payload) }
        }
    }

    @Test fun authenticationRejectsUnsafeServiceDestinations() {
        listOf("http://example.test", "https://name:password@example.test", "https://example.test?key=x", "https://example.test#fragment").forEach {
            assertThrows(Exception::class.java) { AccountNetwork.parseAuthentication(it, authentication()) }
        }
    }

    @Test fun sessionExpiryIsDeterministicAndFailsClosed() {
        val session = AccountNetwork.parseAuthentication("https://example.test", authentication()).session
        val expires = OffsetDateTime.parse(session.expiresAt).toInstant().toEpochMilli()
        assertFalse(accountSessionExpired(session, expires - 1))
        assertTrue(accountSessionExpired(session, expires))
        assertTrue(accountSessionExpired(session.copy(token = ""), expires - 1))
        assertTrue(accountSessionExpired(session.copy(expiresAt = "bad date"), expires - 1))
    }

    private fun json(payload: String) = appJson.parseToJsonElement(payload) as JsonObject
}
