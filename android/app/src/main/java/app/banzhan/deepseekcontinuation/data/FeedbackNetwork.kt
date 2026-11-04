package app.banzhan.deepseekcontinuation.data

import java.io.IOException
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Feedback transport never exposes upstream detail or parser excerpts in exceptions. */
object FeedbackNetwork {
    private const val MAX_IMAGE_BYTES = 1_048_576
    private const val MAX_BODY_BYTES = 4_718_592
    private const val MAX_JSON_RESPONSE_BYTES = 2_097_152L
    private val contentType = "application/json; charset=utf-8".toMediaType()
    private val statuses = setOf("pending", "approved", "ignored")
    private val imageTypes = setOf("image/jpeg", "image/png")

    suspend fun submit(settings: ServiceSettings, submission: FeedbackSubmission): FeedbackRecord {
        val payload = submissionPayload(submission)
        val request = ownerRequest(settings).post(payload.toRequestBody(contentType)).build()
        return exchange(request, mutation = true) { parseRecord(it) }
    }

    suspend fun listMine(settings: ServiceSettings, limit: Int = 50, offset: Int = 0): FeedbackPage {
        validatePage(limit, offset)
        val url = SummaryNetwork.companionEndpoint(settings.companionUrl, "feedback").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString()).build()
        val request = ownerRequest(settings).url(url).get().build()
        return exchange(request, mutation = false) { parsePage(it) }
    }

    suspend fun listAdmin(prefs: DeveloperPrefs, status: String? = null, limit: Int = 50, offset: Int = 0): FeedbackPage {
        validatePage(limit, offset)
        require(status == null || status in statuses) { "请选择有效的反馈状态。" }
        val url = SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", "feedback").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString())
            .apply { if (status != null) addQueryParameter("status", status) }.build()
        val request = adminRequest(prefs).url(url).get().build()
        return exchange(request, mutation = false) { parsePage(it, admin = true) }
    }

    suspend fun getAdmin(prefs: DeveloperPrefs, id: String): FeedbackRecord {
        validId(id)
        return exchange(adminRequest(prefs, id).get().build(), mutation = false) { parseRecord(it, admin = true) }
    }

    suspend fun updateAdmin(prefs: DeveloperPrefs, id: String, status: String, adminNote: String,
        expectedRevision: Int): FeedbackRecord {
        validId(id)
        val payload = updatePayload(status, adminNote, expectedRevision)
        val request = adminRequest(prefs, id).patch(payload.toRequestBody(contentType)).build()
        return exchange(request, mutation = true) { parseRecord(it, admin = true) }
    }

    suspend fun loadImage(settings: ServiceSettings, feedbackId: String, imageId: String): ByteArray {
        validId(feedbackId)
        validId(imageId)
        return imageResponse(ownerRequest(settings, feedbackId, "images", imageId).header("Accept", "image/jpeg, image/png").get().build())
    }

    suspend fun loadAdminImage(prefs: DeveloperPrefs, feedbackId: String, imageId: String): ByteArray {
        validId(feedbackId)
        validId(imageId)
        return imageResponse(adminRequest(prefs, feedbackId, "images", imageId).header("Accept", "image/jpeg, image/png").get().build())
    }

    internal fun submissionPayload(submission: FeedbackSubmission): String {
        require(submission.idempotencyKey.length in 8..200 && submission.idempotencyKey.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_.:-"
        }) { "反馈提交编号无效，请重新打开意见箱。" }
        require(submission.content.isNotBlank() && submission.content.length <= 5000) { "请填写1至5000字的意见内容。" }
        require(submission.contactType in setOf("qq", "wechat")) { "请选择QQ或微信作为联系方式。" }
        require(submission.contact.isNotBlank() && submission.contact.length <= 128) { "请填写有效的QQ号或微信号。" }
        require(submission.appVersion.length <= 100) { "应用版本信息过长。" }
        require(submission.images.size <= 3) { "最多添加3张图片。" }
        submission.images.forEach { image ->
            require(image.dataBase64.length in 1..((MAX_IMAGE_BYTES + 2) / 3 * 4) && image.dataBase64.length % 4 == 0) {
                "图片数据大小或格式不正确，请重新选择图片。"
            }
            val bytes = try { Base64.getDecoder().decode(image.dataBase64) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("图片数据格式不正确，请重新选择图片。")
            }
            validateImageBytes(image.mimeType, bytes)
        }
        val payload = buildJsonObject {
            put("idempotency_key", submission.idempotencyKey)
            put("content", submission.content)
            put("contact_type", submission.contactType)
            put("contact", submission.contact)
            put("app_version", submission.appVersion)
            put("images", buildJsonArray {
                submission.images.forEach { image -> add(buildJsonObject {
                    put("mime_type", image.mimeType)
                    put("data_base64", image.dataBase64)
                }) }
            })
        }.toString()
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "反馈内容过大，请减少图片后重试。" }
        return payload
    }

    internal fun updatePayload(status: String, adminNote: String, expectedRevision: Int): String {
        require(status in statuses) { "请选择有效的反馈状态。" }
        require(adminNote.length <= 5000) { "审核批注最多5000字。" }
        require(expectedRevision >= 1) { "反馈版本已失效，请刷新后再保存。" }
        return buildJsonObject {
            put("status", status)
            put("admin_note", adminNote)
            put("expected_revision", expectedRevision)
        }.toString()
    }

    internal fun parseRecord(payload: String, admin: Boolean = false): FeedbackRecord =
        record(objectResponse(payload), admin)

    internal fun parsePage(payload: String, admin: Boolean = false): FeedbackPage {
        val root = objectResponse(payload)
        val limit = root.integer("limit")
        val offset = root.integer("offset")
        validatePage(limit, offset)
        val total = (root["total"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        require(total != null && total >= 0) { "反馈列表总数无效。" }
        val items = root["items"] as? JsonArray ?: error("反馈列表格式无效。")
        require(items.size <= limit) { "反馈列表超出分页范围。" }
        return FeedbackPage(items.map { record(it as? JsonObject ?: error("反馈条目格式无效。"), admin) }, total, limit, offset)
    }

    private fun record(root: JsonObject, admin: Boolean): FeedbackRecord {
        val id = root.text("id", 1, 200).also(::validId)
        val contactType = root.text("contact_type", 1, 10)
        require(contactType in setOf("qq", "wechat")) { "反馈联系方式类型无效。" }
        val status = root.text("status", 1, 20)
        require(status in statuses) { "反馈审核状态无效。" }
        val images = root["images"] as? JsonArray ?: error("反馈图片信息无效。")
        require(images.size <= 3) { "反馈图片数量无效。" }
        val imageInfos = images.map { value ->
            val image = value as? JsonObject ?: error("反馈图片信息无效。")
            val imageId = image.text("id", 1, 200).also(::validId)
            val mime = image.text("mime_type", 1, 30)
            val size = image.integer("byte_size")
            require(mime in imageTypes && size in 1..MAX_IMAGE_BYTES) { "反馈图片信息无效。" }
            FeedbackImageInfo(imageId, mime, size)
        }
        val processing = when (val value = root["processing"]) {
            null, JsonNull -> null
            is JsonObject -> parseProcessing(value)
            else -> error("反馈处理结果格式无效。")
        }
        val history = if (admin && "processing_history" in root) {
            val values = root["processing_history"] as? JsonArray ?: error("反馈处理历史格式无效。")
            values.map { parseProcessing(it as? JsonObject ?: error("反馈处理历史格式无效。")) }
        } else emptyList()
        val revision = if (admin) root.integer("revision").also { require(it >= 1) { "反馈版本无效。" } } else null
        return FeedbackRecord(id, root.text("content", 1, 5000), contactType, root.text("contact", 1, 128),
            root.text("app_version", 0, 100), status, root.timestamp("created_at"), root.timestamp("updated_at"),
            imageInfos, processing, if (admin) root.text("owner_id", 1, 200) else null,
            if (admin) root.text("admin_note", 0, 5000) else null, revision, history)
    }

    private fun parseProcessing(root: JsonObject): FeedbackProcessing {
        val revision = root.integer("revision")
        require(revision >= 1) { "反馈处理版本无效。" }
        return FeedbackProcessing(revision, root.text("result_status", 1, 100), root.text("result_note", 1, 5000), root.timestamp("processed_at"))
    }

    internal fun validateImageBytes(mimeType: String, bytes: ByteArray) {
        require(mimeType in imageTypes && bytes.size in 1..MAX_IMAGE_BYTES) { "图片格式或大小无效，请重新选择图片。" }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val matches = if (mimeType == "image/png") bytes.size >= png.size && bytes.take(png.size).toByteArray().contentEquals(png)
        else bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        require(matches) { "图片格式与文件内容不匹配，请重新选择图片。" }
    }

    internal fun safeFailure(statusCode: Int?, mutation: Boolean, ambiguous: Boolean): SummaryFailure {
        val message = when (statusCode) {
            401 -> "意见箱访问凭据已失效，请重新连接服务或登录开发者设置。"
            403 -> "当前身份无法访问这条反馈。"
            404, 410 -> "反馈记录或图片已不存在，请刷新列表。"
            409 -> "反馈已更新或提交编号冲突，请刷新后核对再试。"
            413 -> "反馈或图片超过大小限制，请减少图片后重试。"
            422 -> "反馈格式不正确，请检查文字、联系方式和图片。"
            429 -> "反馈提交次数已达上限，请稍后再试。"
            503 -> "意见箱暂不可用，请稍后再试。"
            else -> if (mutation && ambiguous) "连接中断，尚无法确认反馈是否已保存，请保留原内容后重试。"
                else "暂时无法读取或保存反馈，请稍后再试。"
        }
        return SummaryFailure(message, ambiguous = ambiguous, statusCode = statusCode)
    }

    private suspend fun <T> exchange(request: Request, mutation: Boolean, parse: (String) -> T): T = try {
        parse(SummaryNetwork.exchange(request, mutation, maxResponseBytes = MAX_JSON_RESPONSE_BYTES))
    } catch (error: CancellationException) {
        throw error
    } catch (error: SummaryFailure) {
        throw safeFailure(error.statusCode, mutation, error.ambiguous)
    } catch (error: Exception) {
        throw SummaryFailure("意见箱响应格式异常，请稍后刷新。", ambiguous = mutation)
    }

    private suspend fun imageResponse(request: Request): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = SummaryNetwork.client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(safeFailure(null, mutation = false, ambiguous = false))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!response.isSuccessful) throw safeFailure(response.code, mutation = false, ambiguous = false)
                        val body = response.body ?: error("empty image")
                        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase(Locale.ROOT).orEmpty()
                        require(mime in imageTypes && body.contentLength() <= MAX_IMAGE_BYTES) { "invalid image response" }
                        val source = body.source()
                        source.request(MAX_IMAGE_BYTES.toLong() + 1)
                        require(source.buffer.size <= MAX_IMAGE_BYTES) { "oversize image response" }
                        val bytes = source.readByteArray()
                        validateImageBytes(mime, bytes)
                        if (continuation.isActive) continuation.resume(bytes)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        error as? SummaryFailure ?: SummaryFailure("图片格式或大小无效，请刷新后再试。", ambiguous = false)
                    )
                }
            }
        })
    }

    private fun ownerRequest(settings: ServiceSettings, vararg path: String): Request.Builder =
        SummaryNetwork.authenticated(SummaryNetwork.companionEndpoint(settings.companionUrl, "feedback", *path), settings.trialToken)

    private fun adminRequest(prefs: DeveloperPrefs, vararg path: String): Request.Builder =
        SummaryNetwork.authenticated(SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", "feedback", *path), prefs.sessionToken)

    private fun validatePage(limit: Int, offset: Int) {
        require(limit in 1..100 && offset >= 0) { "反馈列表分页参数无效。" }
    }

    private fun validId(value: String) {
        require(value.length in 1..200 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_" }) {
            "反馈或图片编号无效，请刷新列表。"
        }
    }

    private fun objectResponse(payload: String): JsonObject =
        SummaryProtocol.json.parseToJsonElement(payload) as? JsonObject ?: error("反馈响应格式无效。")

    private fun JsonObject.text(key: String, minimum: Int, maximum: Int): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(value != null && value.length in minimum..maximum && (minimum == 0 || value.isNotBlank())) { "反馈文本字段格式无效。" }
        return value
    }

    private fun JsonObject.integer(key: String): Int {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(value != null) { "反馈数值字段格式无效。" }
        return value
    }

    private fun JsonObject.timestamp(key: String): String = text(key, 1, 100).also { OffsetDateTime.parse(it) }
}
