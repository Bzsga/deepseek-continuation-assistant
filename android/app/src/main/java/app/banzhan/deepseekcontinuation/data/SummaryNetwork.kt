package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class SummaryFailure(
    message: String,
    val ambiguous: Boolean = true,
    val expired: Boolean = false,
    val statusCode: Int? = null
) : IOException(message)

data class RemoteJob(
    val id: String,
    val status: String,
    val entries: List<ArchiveEntry> = emptyList(),
    val message: String = "",
    val usageKnown: Boolean? = null
)

data class CompanionQuota(
    val enabled: Boolean,
    val remainingCny: Double?,
    val perJobLimitCny: Double?,
    val model: String,
    val message: String,
    val dailyLimit: Int? = null,
    val dailyUsed: Int? = null,
    val dailyRemaining: Int? = null,
    val userDailyLimit: Int? = null,
    val userDailyUsed: Int? = null,
    val userDailyRemaining: Int? = null,
    val resetAt: String? = null,
    val accessMode: String = "invitation",
    val contextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val dailyReserved: Int? = null,
    val userDailyReserved: Int? = null
) {
    fun organizationBlocker(): String? {
        if (!enabled) return "免费智能整理暂未开放，可以先带走原文，或使用自己的 AI 服务。"
        if (accessMode == "public") {
            if (dailyRemaining == null || userDailyRemaining == null || dailyRemaining < 0 || userDailyRemaining < 0) {
                return "免费整理次数信息不完整，请刷新后再试"
            }
            if (dailyRemaining == 0) return "今日免费整理总次数已用完，请明天再试"
            if (userDailyRemaining == 0) return "你今日的免费整理次数已用完，请明天再试"
            return null
        }
        if (remainingCny == null || perJobLimitCny == null || !remainingCny.isFinite() ||
            !perJobLimitCny.isFinite() || remainingCny < 0 || perJobLimitCny < 0) {
            return "试用额度信息不完整，请刷新后再试"
        }
        if (remainingCny == 0.0) return "试用额度已用完，请联系服务提供者"
        if (perJobLimitCny == 0.0) return "试用服务单次额度为零，请联系服务提供者"
        return null
    }
}

data class CompanionSession(val token: String, val quota: CompanionQuota) {
    override fun toString(): String = "CompanionSession(token=[hidden], quota=$quota)"
}

/** All calls are explicit. A malformed result never triggers another billable request. */
object SummaryNetwork {
    internal val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
    private val contentType = "application/json; charset=utf-8".toMediaType()

    suspend fun testConnection(settings: ServiceSettings): ConnectionTestResult {
        val body = connectionTestPayload(settings)
        val request = authenticated(chatEndpoint(settings.baseUrl), settings.apiKey)
            .post(body.toRequestBody(contentType)).build()
        val payload = exchange(request, mutation = true, secrets = listOf(settings.apiKey))
        return try {
            parseConnectionTest(payload, listOf(settings.apiKey))
        } catch (error: Exception) {
            throw SummaryFailure("服务已返回响应，但短连接测试未得到可读文本，请检查模型与接口配置", ambiguous = true)
        }
    }

    internal fun connectionTestPayload(settings: ServiceSettings): String {
        require(settings.model.isNotBlank() && settings.model.length <= 200 && settings.model.none { it.isISOControl() }) {
            "请填写有效模型名称"
        }
        return buildJsonObject {
            put("model", settings.model.trim())
            put("max_tokens", 32)
            put("stream", false)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "Reply with OK.") })
            })
            if (settings.model.trim().startsWith("deepseek", ignoreCase = true)) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
        }.toString()
    }

    internal fun parseConnectionTest(payload: String, secrets: List<String> = emptyList()): ConnectionTestResult {
        val root = objectResponse(payload)
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: error("连接测试缺少 choices")
        val message = choice["message"] as? JsonObject ?: error("连接测试缺少 message")
        val content = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(!content.isNullOrBlank()) { "连接测试缺少可读文本" }
        val bounded = redactSecrets(content.trim(), secrets).take(160)
        val reply = if (bounded.lastOrNull()?.isHighSurrogate() == true) bounded.dropLast(1) else bounded
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }?.let { redactSecrets(it, secrets).take(200) }
        return ConnectionTestResult(reply, model)
    }

    suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>): List<ArchiveEntry> {
        val body = SummaryProtocol.requestBody(settings, messages)
        val request = authenticated(chatEndpoint(settings.baseUrl), settings.apiKey)
            .post(body.toRequestBody(contentType)).build()
        val response = exchange(request, mutation = true, secrets = listOf(settings.apiKey))
        return parseResult(ambiguous = true) {
            val root = objectResponse(response)
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: error("API 未返回可读取的整理内容")
            val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            require(reason == null || reason == "stop") { "API 整理输出未完整结束（$reason），请检查输出预算" }
            val message = choice["message"] as? JsonObject ?: error("API 缺少 message 内容")
            val content = message["content"] as? JsonPrimitive ?: error("API 未返回文本内容")
            require(content.isString && content.content.isNotBlank()) { "API 返回了空文本内容" }
            // reasoning_content is intentionally never read or stored.
            SummaryProtocol.validateEntries(content.content, messages)
        }
    }

    suspend fun redeem(settings: ServiceSettings, code: String): String {
        require(code.trim().isNotEmpty() && code.length <= 256) { "请输入有效邀请码" }
        val body = buildJsonObject { put("code", code.trim()) }.toString()
        val request = Request.Builder().url(companionEndpoint(settings.companionUrl, "invitations", "redeem"))
            .post(body.toRequestBody(contentType)).build()
        return parseResult(ambiguous = true) {
            val root = objectResponse(exchange(request, mutation = true))
            val token = (root["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(token.isNotBlank() && token.length <= 8192) { "试用服务未返回有效令牌" }
            token
        }
    }

    suspend fun getQuota(settings: ServiceSettings): CompanionQuota {
        val request = authenticated(companionEndpoint(settings.companionUrl, "quota"), settings.trialToken).get().build()
        val payload = exchange(request, mutation = false, secrets = listOf(settings.trialToken))
        return try {
            parseQuota(payload)
        } catch (error: Exception) {
            throw SummaryFailure("无法读取有效试用额度，请稍后刷新。${error.message.orEmpty().take(200)}", ambiguous = false)
        }
    }

    suspend fun createSession(settings: ServiceSettings): CompanionSession {
        val request = Request.Builder().url(companionEndpoint(settings.companionUrl, "sessions"))
            .post("{}".toRequestBody(contentType)).build()
        val payload = exchange(request, mutation = true)
        return try {
            parseSession(payload)
        } catch (error: Exception) {
            throw SummaryFailure("免费整理服务返回了无效会话，请稍后再试", ambiguous = false)
        }
    }

    internal fun parseSession(payload: String): CompanionSession {
        val root = objectResponse(payload)
        val token = (root["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        require(token.isNotBlank() && token.length <= 8192 && token.all { it.code in 33..126 }) { "免费会话令牌格式无效" }
        val quota = root["quota"] as? JsonObject ?: error("免费会话缺少额度信息")
        return CompanionSession(token, parseQuota(quota.toString()))
    }

    suspend fun quota(settings: ServiceSettings): String {
        val quota = getQuota(settings)
        return buildList {
            if (quota.accessMode == "public") {
                add(if (quota.enabled) "免费整理已开放" else "免费整理暂未开放")
                quota.dailyRemaining?.let { add("今日全站剩余 ${it} 次") }
                quota.userDailyRemaining?.let { add("今日个人剩余 ${it} 次") }
            } else {
                add(if (quota.enabled) "试用服务已启用" else "试用服务暂不可用")
                quota.remainingCny?.let { add("剩余额度 ¥%.4f".format(it)) }
                quota.perJobLimitCny?.let { add("单任务上限 ¥%.4f".format(it)) }
            }
            if (quota.model.isNotBlank()) add("模型 ${quota.model}")
            if (quota.message.isNotBlank()) add(quota.message)
        }.joinToString(" · ")
    }

    internal fun parseQuota(payload: String): CompanionQuota {
        val root = objectResponse(payload)
        val enabled = (root["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(enabled != null) { "试用额度响应缺少有效启用状态" }
        val accessMode = root["access_mode"]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
        } ?: if ("access_mode" in root) error("整理服务访问方式无效") else "invitation"
        require(accessMode in setOf("public", "invitation")) { "整理服务访问方式无效" }
        fun amount(key: String): Double? {
            if (accessMode == "public" && (root[key] == null || root[key] is JsonNull)) return null
            val value = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            require(value != null && value.isFinite() && value >= 0) { "试用额度响应中的 $key 无效" }
            return value
        }
        fun displayText(key: String, maximum: Int): String {
            val element = root[key] ?: return ""
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(value != null) { "试用额度响应中的 $key 格式无效" }
            return value.take(maximum)
        }
        fun counter(key: String): Int? {
            if (root[key] == null && accessMode != "public") return null
            val value = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            require(value != null && value >= 0) { "免费整理响应中的 $key 无效" }
            return value
        }
        val resetAt = if (root["reset_at"] == null && accessMode != "public") null else displayText("reset_at", 100).also {
            require(it.isNotBlank()) { "免费整理响应缺少重置时间" }
            java.time.OffsetDateTime.parse(it)
        }
        val contextTokens = counter("context_tokens")
        val maxOutputTokens = counter("max_output_tokens")
        if (contextTokens != null || maxOutputTokens != null) {
            require(contextTokens != null && maxOutputTokens != null && contextTokens in 512..262_144 &&
                maxOutputTokens in 256..8192 && contextTokens > maxOutputTokens) { "免费整理响应的上下文预算无效" }
        }
        return CompanionQuota(enabled, amount("remaining_cny"), amount("per_job_limit_cny"),
            displayText("model", 160), displayText("message", 500),
            counter("daily_limit"), counter("daily_used"), counter("daily_remaining"),
            counter("user_daily_limit"), counter("user_daily_used"), counter("user_daily_remaining"), resetAt, accessMode,
            contextTokens, maxOutputTokens, counter("daily_reserved"), counter("user_daily_reserved"))
    }

    suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, idempotencyKey: String): RemoteJob {
        require(messages.isNotEmpty()) { "没有可整理的消息" }
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200) { "任务幂等编号无效" }
        val payload = buildJsonObject {
            put("idempotency_key", idempotencyKey)
            put("messages", SummaryProtocol.messagesJson(messages))
        }.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= 512_000) { "试用请求过大，请减少选择范围" }
        val request = authenticated(companionEndpoint(settings.companionUrl, "jobs"), settings.trialToken)
            .post(payload.toRequestBody(contentType)).build()
        return parseResult(ambiguous = true) {
            parseJob(exchange(request, mutation = true, secrets = listOf(settings.trialToken)))
        }
    }

    suspend fun poll(settings: ServiceSettings, jobId: String): RemoteJob {
        validJobId(jobId)
        val request = authenticated(companionEndpoint(settings.companionUrl, "jobs", jobId), settings.trialToken).get().build()
        return parseResult(ambiguous = false) {
            parseJob(exchange(request, mutation = false, secrets = listOf(settings.trialToken)))
        }
    }

    suspend fun cancel(settings: ServiceSettings, jobId: String): RemoteJob {
        validJobId(jobId)
        val request = authenticated(companionEndpoint(settings.companionUrl, "jobs", jobId, "cancel"), settings.trialToken)
            .post("{}".toRequestBody(contentType)).build()
        return parseResult(ambiguous = true) {
            parseJob(exchange(request, mutation = true, secrets = listOf(settings.trialToken)))
        }
    }

    internal fun chatEndpoint(base: String): HttpUrl {
        val url = checkedBase(base)
        if (url.pathSegments.filter { it.isNotEmpty() }.takeLast(2) == listOf("chat", "completions")) return url
        return url.newBuilder().apply {
            if (url.pathSegments.lastOrNull().isNullOrEmpty()) removePathSegment(url.pathSegments.lastIndex)
            addPathSegment("chat")
            addPathSegment("completions")
        }.build()
    }

    internal fun companionEndpoint(base: String, vararg segments: String): HttpUrl {
        val url = checkedBase(base)
        return url.newBuilder().apply {
            if (url.pathSegments.lastOrNull().isNullOrEmpty()) removePathSegment(url.pathSegments.lastIndex)
            if (url.pathSegments.filter { it.isNotEmpty() }.lastOrNull() != "v1") addPathSegment("v1")
            segments.forEach { addPathSegment(it) }
        }.build()
    }

    internal fun checkedBase(base: String): HttpUrl {
        val url = base.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请填写有效的 HTTPS 服务地址")
        require(url.isHttps) { "服务地址必须使用 HTTPS" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "服务地址不能包含用户名、密码、查询参数或片段"
        }
        return url
    }

    internal fun authenticated(url: HttpUrl, secret: String): Request.Builder {
        require(secret.isNotBlank()) { "请先配置密钥或兑换试用令牌" }
        require(secret.length <= 8192 && secret.all { it.code in 33..126 }) { "密钥或令牌格式无效" }
        return Request.Builder().url(url).header("Authorization", "Bearer $secret").header("Accept", "application/json")
    }

    private fun validJobId(jobId: String) {
        require(jobId.isNotBlank() && jobId.length <= 200 && jobId.all { it.isLetterOrDigit() || it in "-_" }) { "服务返回的任务编号无效" }
    }

    private fun objectResponse(payload: String): JsonObject =
        SummaryProtocol.json.parseToJsonElement(payload) as? JsonObject ?: error("服务响应须为 JSON 对象")

    internal fun parseJob(payload: String): RemoteJob {
        val root = objectResponse(payload)
        fun string(key: String): String = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("服务响应缺少 $key")
        val id = string("id").also(::validJobId)
        val status = string("status")
        require(status in setOf("queued", "running", "completed", "failed", "uncertain", "cancelled")) { "服务返回未知任务状态" }
        val entries = if (status == "completed" || root["entries"] is JsonArray) SummaryProtocol.parseEntries(root["entries"]) else emptyList()
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(1000)
        val usageKnown = if ("usage_known" in root) {
            (root["usage_known"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: error("服务返回的用量状态无效")
        } else null
        return RemoteJob(id, status, entries, message, usageKnown)
    }

    private suspend fun <T> parseResult(ambiguous: Boolean, block: suspend () -> T): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: SummaryFailure) {
        throw error
    } catch (error: Exception) {
        throw SummaryFailure("${error.message ?: "服务响应格式无法识别"}。任务已暂停，原文与已完成分段已保留", ambiguous)
    }

    internal fun httpFailure(statusCode: Int, detail: String, mutation: Boolean): SummaryFailure {
        if (statusCode == 410) return SummaryFailure(
            "远端整理结果已过期，继续会重新发起整理请求，请确认后再试。",
            ambiguous = false,
            expired = true,
            statusCode = statusCode
        )
        val reason = if (detail.isBlank()) "请检查服务地址、凭据和额度" else detail
        return SummaryFailure("服务返回 HTTP $statusCode，$reason", mutation && (statusCode >= 500 || statusCode == 408),
            statusCode = statusCode)
    }

    internal fun redactSecrets(value: String, secrets: List<String>): String =
        secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
            .fold(value) { text, secret -> text.replace(secret, "[已隐藏]") }

    internal suspend fun exchange(request: Request, mutation: Boolean, secrets: List<String> = emptyList(),
        maxResponseBytes: Long = SummaryProtocol.MAX_RESPONSE_BYTES): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(SummaryFailure(
                        if (mutation) "连接中断或网络不可用，尚无法确认服务是否已处理请求。任务已暂停" else "暂时无法连接服务，请检查网络后继续",
                        ambiguous = mutation
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (response.code == 410) throw httpFailure(410, "", mutation)
                            val body = response.body ?: throw SummaryFailure("服务返回空响应", mutation)
                            if (body.contentLength() > maxResponseBytes) throw SummaryFailure("服务响应过大，已暂停处理", mutation)
                            val source = body.source()
                            source.request(maxResponseBytes + 1)
                            if (source.buffer.size > maxResponseBytes) throw SummaryFailure("服务响应过大，已暂停处理", mutation)
                            val payload = source.readUtf8()
                            if (!response.isSuccessful) {
                                val detail = runCatching {
                                    val errorObject = objectResponse(payload)
                                    (errorObject["detail"] as? JsonPrimitive)?.contentOrNull
                                        ?: (errorObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                                }.getOrNull().orEmpty()
                                val safeDetail = redactSecrets(detail, secrets).take(500)
                                throw httpFailure(response.code, safeDetail, mutation)
                            }
                            if (continuation.isActive) continuation.resume(payload)
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            error as? SummaryFailure ?: SummaryFailure("读取服务响应中断，任务已暂停", mutation)
                        )
                    }
                }
            })
        }
}
