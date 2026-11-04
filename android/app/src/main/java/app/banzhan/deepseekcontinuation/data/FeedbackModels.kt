package app.banzhan.deepseekcontinuation.data

import java.util.UUID

data class FeedbackDraft(
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val content: String = "",
    val contactType: String = "qq",
    val contact: String = "",
    val images: List<FeedbackDraftImage> = emptyList(),
) {
    override fun toString(): String = "FeedbackDraft(images=${images.size}, privateFields=[hidden])"
}

data class FeedbackDraftImage(val id: String, val jpegBytes: ByteArray, val width: Int, val height: Int) {
    override fun toString(): String = "FeedbackDraftImage(id=$id, width=$width, height=$height, bytes=[hidden])"
}

data class FeedbackUploadImage(val mimeType: String, val dataBase64: String) {
    override fun toString(): String = "FeedbackUploadImage(mimeType=$mimeType, data=[hidden])"
}

data class FeedbackSubmission(
    val idempotencyKey: String, val content: String, val contactType: String,
    val contact: String, val appVersion: String, val images: List<FeedbackUploadImage>,
) {
    override fun toString(): String = "FeedbackSubmission(images=${images.size}, privateFields=[hidden])"
}

data class FeedbackImageInfo(val id: String, val mimeType: String, val byteSize: Int)

data class FeedbackProcessing(val revision: Int, val resultStatus: String, val resultNote: String, val processedAt: String) {
    override fun toString(): String = "FeedbackProcessing(revision=$revision, resultStatus=$resultStatus, resultNote=[hidden])"
}

data class FeedbackRecord(
    val id: String, val content: String, val contactType: String, val contact: String,
    val appVersion: String, val status: String, val createdAt: String, val updatedAt: String,
    val images: List<FeedbackImageInfo>, val processing: FeedbackProcessing? = null,
    val ownerId: String? = null, val adminNote: String? = null, val revision: Int? = null,
    val processingHistory: List<FeedbackProcessing> = emptyList(),
) {
    override fun toString(): String = "FeedbackRecord(id=$id, status=$status, images=${images.size}, privateFields=[hidden])"
}

data class FeedbackPage(val items: List<FeedbackRecord>, val total: Long, val limit: Int, val offset: Int)

internal fun validateFeedbackDraft(draft: FeedbackDraft) {
    require(draft.content.trim().length in 1..5000) { "请填写1至5000字的意见内容。" }
    require(draft.contactType in setOf("qq", "wechat")) { "请选择QQ或微信作为联系方式。" }
    require(draft.contact.trim().length in 1..128) { "请填写有效的QQ号或微信号。" }
    require(draft.images.size <= 3) { "最多添加3张图片。" }
    require(draft.images.all { it.jpegBytes.isNotEmpty() && it.jpegBytes.size <= FeedbackImageProcessor.MAX_IMAGE_BYTES }) {
        "图片未完成处理，请删除后重新添加。"
    }
}
