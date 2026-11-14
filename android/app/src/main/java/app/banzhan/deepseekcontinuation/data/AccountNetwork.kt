package app.banzhan.deepseekcontinuation.data

import java.text.Normalizer
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Account operations are explicit and independent of anonymous/BYOK identities. */
internal interface AccountTransport {
    suspend fun register(serviceUrl: String, username: String, password: String,
        invitationCode: String? = null): AccountAuthentication
    suspend fun login(serviceUrl: String, username: String, password: String): AccountAuthentication
    suspend fun me(session: AccountSession): AccountProfile
    suspend fun logout(session: AccountSession)
}

internal object AccountNetwork : AccountTransport {
    private val contentType = "application/json; charset=utf-8".toMediaType()

    override suspend fun register(serviceUrl: String, username: String, password: String,
        invitationCode: String?): AccountAuthentication {
        val body = registrationPayload(username, password, invitationCode)
        val request = Request.Builder().url(endpoint(serviceUrl, "register"))
            .post(body.toRequestBody(contentType)).build()
        val payload = accountExchange(request, listOfNotNull(password, invitationCode), registering = true)
        return parseResponse { parseAuthentication(serviceUrl, payload) }
    }

    override suspend fun login(serviceUrl: String, username: String, password: String): AccountAuthentication {
        val body = loginPayload(username, password)
        val request = Request.Builder().url(endpoint(serviceUrl, "login"))
            .post(body.toRequestBody(contentType)).build()
        val payload = accountExchange(request, listOf(password))
        return parseResponse { parseAuthentication(serviceUrl, payload) }
    }

    override suspend fun me(session: AccountSession): AccountProfile {
        val request = SummaryNetwork.authenticated(endpoint(session.serviceUrl, "me"), session.token).get().build()
        val payload = accountExchange(request, listOf(session.token), mutation = false)
        return parseResponse {
            parseProfile(payload).also { require(it.user.id == session.user.id) { "账户身份不一致" } }
        }
    }

    override suspend fun logout(session: AccountSession) {
        val request = SummaryNetwork.authenticated(endpoint(session.serviceUrl, "logout"), session.token)
            .post("{}".toRequestBody(contentType)).build()
        accountExchange(request, listOf(session.token))
    }

    internal fun registrationPayload(username: String, password: String, invitationCode: String?): String {
        val normalized = normalizedUsername(username)
        validatePassword(password)
        val invitation = invitationCode?.trimAccountWhitespace()?.takeIf { it.isNotEmpty() }
        require(invitation == null || (invitation.length <= 256 && invitation.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
        })) { "邀请码格式无效，请检查后重试。" }
        return buildJsonObject {
            put("username", normalized)
            put("password", password)
            if (invitation != null) put("invitation_code", invitation)
        }.toString()
    }

    internal fun loginPayload(username: String, password: String): String {
        val normalized = normalizedUsername(username)
        validatePassword(password)
        return buildJsonObject { put("username", normalized); put("password", password) }.toString()
    }

    internal fun parseAuthentication(serviceUrl: String, payload: String): AccountAuthentication {
        val root = objectResponse(payload)
        val token = root.text("token")
        require(token.isNotBlank() && token.length <= 8192 && token.all { it.code in 33..126 }) { "账户令牌格式无效" }
        val expiresAt = root.timestamp("expires_at")
        val user = parseUser(root["user"] as? JsonObject ?: error("账户响应缺少 user"))
        val quota = SummaryNetwork.parseQuota((root["quota"] as? JsonObject ?: error("账户响应缺少 quota")).toString())
        return AccountAuthentication(AccountSession(
            SummaryNetwork.checkedBase(serviceUrl).toString().trimEnd('/'), token, expiresAt, user,
        ), quota)
    }

    internal fun parseProfile(payload: String): AccountProfile {
        val root = objectResponse(payload)
        val user = parseUser(root["user"] as? JsonObject ?: error("账户响应缺少 user"))
        val quota = SummaryNetwork.parseQuota((root["quota"] as? JsonObject ?: error("账户响应缺少 quota")).toString())
        return AccountProfile(user, quota)
    }

    internal fun normalizedUsername(username: String): String {
        val normalized = Normalizer.normalize(username.trimAccountWhitespace(), Normalizer.Form.NFKC)
        val points = normalized.codePoints().toArray()
        require(points.size in 3..32 && points.all { point ->
            Character.isLetterOrDigit(point) || Character.getType(point) in setOf(
                Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            ) || point == '_'.code || point == '-'.code
        }) { "用户名须为 3–32 个字，可使用汉字、字母、数字、下划线或短横线。" }
        return normalized
    }

    private fun validatePassword(password: String) {
        var index = 0
        while (index < password.length) {
            val char = password[index]
            if (char.isHighSurrogate()) {
                require(index + 1 < password.length && password[index + 1].isLowSurrogate()) { "密码包含无效字符。" }
                index += 2
            } else {
                require(!char.isLowSurrogate()) { "密码包含无效字符。" }
                index++
            }
        }
        require(password.codePointCount(0, password.length) in 8..128) { "密码须为 8–128 个字符。" }
    }

    private fun parseUser(root: JsonObject): AccountUser {
        val id = root.text("id")
        require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) { "账户编号无效" }
        val username = root.text("username")
        require(normalizedUsername(username) == username) { "账户用户名无效" }
        val kind = root.text("registration_kind")
        require(kind in setOf("public", "invitation")) { "账户注册方式无效" }
        return AccountUser(id, username, kind, root.timestamp("created_at"))
    }

    private fun endpoint(serviceUrl: String, operation: String) =
        SummaryNetwork.companionEndpoint(serviceUrl, "accounts", operation)

    private suspend fun accountExchange(request: Request, secrets: List<String>, mutation: Boolean = true,
        registering: Boolean = false): String = try {
        SummaryNetwork.exchange(request, mutation, secrets, maxResponseBytes = 64 * 1024)
    } catch (error: SummaryFailure) {
        if (error.statusCode == null) throw SummaryFailure(
            if (registering && error.ambiguous) "尚无法确认注册是否成功，可以尝试使用刚才的用户名和密码登录。"
            else "暂时无法连接账户服务，请检查网络后重试。",
            ambiguous = error.ambiguous, expired = error.expired, statusCode = error.statusCode,
        )
        throw error
    }

    private fun <T> parseResponse(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Parser excerpts may contain an unexpectedly echoed password or token.
        throw SummaryFailure("账户服务返回的资料无效，请稍后重试。", ambiguous = false)
    }

    private fun objectResponse(payload: String): JsonObject =
        SummaryProtocol.json.parseToJsonElement(payload) as? JsonObject ?: error("账户响应格式无效")

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("账户响应缺少 $key")

    private fun JsonObject.timestamp(key: String): String = text(key).also { OffsetDateTime.parse(it) }

    private fun String.trimAccountWhitespace(): String = trim { it.isWhitespace() || it == '\u0085' }
}
