@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.banzhan.deepseekcontinuation.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.banzhan.deepseekcontinuation.data.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FeedbackScreen(
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("write") }
    var selected by remember { mutableStateOf<FeedbackRecord?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var pickingImages by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var resetConfirmation by remember { mutableStateOf(false) }
    val draft = state.feedbackDraft
    val locked =
        submitting ||
            pickingImages ||
            state.feedbackSubmitting ||
            state.feedbackImageLoading ||
            state.feedbackUncertain
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty() && !locked) {
                pickingImages = true
                imageError = null
                run {
                    try {
                        val available =
                            (3 - controller.state.value.feedbackDraft.images.size).coerceAtLeast(0)
                        require(uris.size <= available) { "还可以添加 $available 张图片，请重新选择。" }
                        for (uri in uris) {
                            val bytes =
                                withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        val output = ByteArrayOutputStream()
                                        val buffer = ByteArray(8192)
                                        var total = 0
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            require(total <= 20 * 1024 * 1024) {
                                                "单张图片请控制在 20 MB 以内。"
                                            }
                                            output.write(buffer, 0, count)
                                        }
                                        output.toByteArray()
                                    } ?: error("无法读取所选图片，请重新选择。")
                                }
                            controller.addFeedbackImage(bytes)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        imageError = error.message ?: "图片未能添加，请重试。"
                    } finally {
                        pickingImages = false
                    }
                }
            }
        }
    LaunchedEffect(page) {
        if (page == "mine") run { controller.refreshMyFeedback(limit = 25, offset = 0) }
    }
    BackHandler(selected != null) { selected = null }
    val detail = selected
    if (detail != null) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { TextButton(onClick = { selected = null }) { Text("返回我的反馈") } }
            if (state.feedbackLastSubmitted?.id == detail.id)
                item { NoticeCard("已收到你的反馈，后续状态会在这里更新。") }
            item { FeedbackDetails(detail, controller, admin = false) }
            item {
                OutlinedButton(
                    onClick = {
                        run {
                            controller.refreshMyFeedback(
                                limit = 25,
                                offset = state.myFeedback?.offset ?: 0,
                            )
                            selected =
                                controller.state.value.myFeedback?.items?.firstOrNull {
                                    it.id == detail.id
                                } ?: detail
                        }
                    },
                    enabled = !state.myFeedbackLoading,
                ) {
                    Text("刷新处理状态")
                }
                state.myFeedbackError?.let { NoticeCard(it) }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionTitle("把想法寄给我们", "问题、建议和使用感受，都可以写在这里。")
            ActionRow {
                FilterChip(page == "write", { page = "write" }, label = { Text("写意见") })
                FilterChip(page == "mine", { page = "mine" }, label = { Text("我的反馈") })
            }
        }
        if (page == "write") {
            state.feedbackLastSubmitted?.let { record ->
                item {
                    NoticeCard("已收到你的反馈，可在「我的反馈」查看状态。")
                    TextButton(onClick = { selected = record }) { Text("查看这条反馈") }
                }
            }
            item {
                OutlinedTextField(
                    draft.content,
                    { controller.updateFeedbackDraft(it, draft.contactType, draft.contact) },
                    Modifier.fillMaxWidth(),
                    label = { Text("意见内容（必填）") },
                    placeholder = { Text("发生了什么，或希望怎样改进？") },
                    minLines = 5,
                    maxLines = 12,
                    supportingText = { Text("${draft.content.length}/5000 字") },
                    isError = draft.content.length > 5000,
                    enabled = !locked,
                )
            }
            item {
                Text("方便联系你的方式", fontWeight = FontWeight.SemiBold)
                ActionRow {
                    listOf("qq" to "QQ", "wechat" to "微信").forEach { (value, label) ->
                        FilterChip(
                            draft.contactType == value,
                            { controller.updateFeedbackDraft(draft.content, value, draft.contact) },
                            label = { Text(label) },
                            enabled = !locked,
                        )
                    }
                }
                OutlinedTextField(
                    draft.contact,
                    { controller.updateFeedbackDraft(draft.content, draft.contactType, it) },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (draft.contactType == "wechat") "微信号（必填）" else "QQ 号（必填）") },
                    singleLine = true,
                    isError = draft.contact.length > 128,
                    enabled = !locked,
                )
            }
            item {
                Text("图片 · ${draft.images.size}/3", fontWeight = FontWeight.SemiBold)
                Text(
                    "提交后，内容、联系方式和图片会发送给开发者。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(draft.images, key = { it.id }) { image ->
                FeedbackImage("draft:${image.id}", "待提交图片") { image.jpegBytes }
                TextButton(
                    onClick = { controller.removeFeedbackImage(image.id) },
                    enabled = !locked,
                ) {
                    Text("移除这张图片")
                }
            }
            item {
                OutlinedButton(
                    onClick = { imagePicker.launch(arrayOf("image/*")) },
                    enabled = !locked && draft.images.size < 3,
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (pickingImages || state.feedbackImageLoading) "正在处理图片…" else "添加图片")
                }
            }
            imageError?.let { item { NoticeCard(it) } }
            state.feedbackError?.let { item { NoticeCard(it) } }
            if (state.feedbackUncertain) item { NoticeCard("提交结果暂时不确定。可以重试同一条反馈，服务器会核对本次提交记录。") }
            item {
                ActionRow {
                    Button(
                        onClick = {
                            if (!submitting) {
                                submitting = true
                                run {
                                    try {
                                        selected = controller.submitFeedback()
                                        page = "mine"
                                    } finally {
                                        submitting = false
                                    }
                                }
                            }
                        },
                        enabled =
                            !submitting &&
                                !pickingImages &&
                                !state.feedbackSubmitting &&
                                !state.feedbackImageLoading &&
                                draft.content.trim().length in 1..5000 &&
                                draft.contact.trim().length in 1..128,
                    ) {
                        Text(
                            if (submitting || state.feedbackSubmitting) "正在提交…"
                            else if (state.feedbackUncertain) "重试本次提交" else "提交意见"
                        )
                    }
                    TextButton(
                        onClick = { resetConfirmation = true },
                        enabled =
                            !submitting &&
                                !pickingImages &&
                                !state.feedbackSubmitting &&
                                !state.feedbackImageLoading,
                    ) {
                        Text("重新填写")
                    }
                }
            }
        } else {
            item {
                TextButton(
                    onClick = { run { controller.refreshMyFeedback(limit = 25, offset = 0) } },
                    enabled = !state.myFeedbackLoading,
                ) {
                    Text("刷新反馈")
                }
            }
            if (state.myFeedbackLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.myFeedbackError?.let { item { NoticeCard(it) } }
            val feedback = state.myFeedback
            if (feedback != null) {
                if (feedback.items.isEmpty()) item { NoticeCard("还没有提交过反馈。") }
                items(feedback.items, key = { it.id }) { record ->
                    FeedbackListItem(record) { selected = record }
                }
                item {
                    FeedbackPagination(feedback, state.myFeedbackLoading) { offset ->
                        run { controller.refreshMyFeedback(limit = 25, offset = offset) }
                    }
                }
            }
        }
    }
    if (resetConfirmation)
        AlertDialog(
            onDismissRequest = { resetConfirmation = false },
            title = { Text("重新填写意见？") },
            text = {
                Text(
                    if (state.feedbackUncertain) "当前内容会被清空。结果不确定的提交可能已被服务器收到，可先在「我的反馈」中查看。"
                    else "将清空当前草稿和已选图片。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.resetFeedbackDraft()
                        imageError = null
                        resetConfirmation = false
                    }
                ) {
                    Text("清空并重新填写")
                }
            },
            dismissButton = { TextButton(onClick = { resetConfirmation = false }) { Text("返回") } },
        )
}

@Composable
internal fun AdminFeedbackScreen(
    state: AppState,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    var status by rememberSaveable { mutableStateOf("pending") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(status, selectedId) {
        val id = selectedId
        if (id == null) run { controller.refreshAdminFeedback(status, limit = 25, offset = 0) }
        else run { controller.loadAdminFeedback(id) }
    }
    BackHandler(selectedId != null) { selectedId = null }
    val detailId = selectedId
    if (detailId != null) {
        val detail = state.adminFeedbackDetail?.takeIf { it.id == detailId }
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ActionRow {
                    TextButton(onClick = { selectedId = null }) { Text("返回意见列表") }
                    TextButton(
                        onClick = { run { controller.loadAdminFeedback(detailId) } },
                        enabled = !state.adminFeedbackDetailLoading,
                    ) {
                        Text("刷新详情")
                    }
                }
            }
            if (state.adminFeedbackDetailLoading)
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.adminFeedbackDetailError?.let { item { NoticeCard(it) } }
            if (detail != null) {
                item { FeedbackDetails(detail, controller, admin = true) }
                item {
                    FeedbackReviewEditor(
                        detail,
                        state.adminFeedbackDetailLoading || state.adminFeedbackDetailError != null,
                        controller,
                        run,
                    )
                }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionTitle("意见审批")
            ActionRow {
                listOf("pending", "approved", "ignored").forEach { value ->
                    FilterChip(
                        status == value,
                        { status = value },
                        label = { Text(feedbackStatus(value)) },
                        enabled = !state.adminFeedbackLoading,
                    )
                }
                TextButton(
                    onClick = {
                        run { controller.refreshAdminFeedback(status, limit = 25, offset = 0) }
                    },
                    enabled = !state.adminFeedbackLoading,
                ) {
                    Text("刷新")
                }
            }
        }
        if (state.adminFeedbackLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.adminFeedbackError?.let { item { NoticeCard(it) } }
        val feedback =
            state.adminFeedback.takeIf {
                !state.adminFeedbackLoading && state.adminFeedbackError == null
            }
        if (feedback != null) {
            if (feedback.items.isEmpty()) item { NoticeCard("当前分类没有反馈。") }
            items(feedback.items, key = { it.id }) { record ->
                FeedbackListItem(record) { selectedId = record.id }
            }
            item {
                FeedbackPagination(feedback, state.adminFeedbackLoading) { offset ->
                    run { controller.refreshAdminFeedback(status, limit = 25, offset = offset) }
                }
            }
        }
    }
}

@Composable
private fun FeedbackReviewEditor(
    record: FeedbackRecord,
    loading: Boolean,
    controller: AppController,
    run: (suspend () -> Unit) -> Unit,
) {
    var note by remember(record.id, record.revision) { mutableStateOf(record.adminNote.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("审批与批注", fontWeight = FontWeight.SemiBold)
        Text(
            "通过后进入处理队列，处理结果回传后会显示在详情中。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            note,
            { note = it },
            Modifier.fillMaxWidth(),
            label = { Text("开发者批注") },
            minLines = 3,
            maxLines = 8,
            enabled = !saving && !loading,
        )
        ActionRow {
            listOf("approved" to "通过", "ignored" to "忽略", record.status to "保存批注").forEach {
                (status, title) ->
                OutlinedButton(
                    onClick = {
                        val revision = record.revision
                        if (!saving && revision != null) {
                            saving = true
                            run {
                                try {
                                    controller.processAdminFeedback(
                                        record.id,
                                        status,
                                        note,
                                        revision,
                                    )
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                    enabled = !saving && !loading && record.revision != null,
                ) {
                    Text(if (saving) "提交中…" else title)
                }
            }
        }
        if (record.revision == null) NoticeCard("详情缺少审批版本，请刷新后重试。")
    }
}

@Composable
private fun FeedbackListItem(record: FeedbackRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(feedbackStatus(record.status), color = Blue, fontWeight = FontWeight.SemiBold)
            Text(record.content, maxLines = 3)
            Text(
                "${readableServerTime(record.createdAt)} · ${record.images.size} 张图片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeedbackDetails(record: FeedbackRecord, controller: AppController, admin: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(feedbackStatus(record.status), color = Blue, fontWeight = FontWeight.SemiBold)
        if (admin && record.revision != null)
            Text("当前审批版本 · ${record.revision}", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(record.content) }
        SelectionContainer {
            Text(
                "${if (record.contactType == "wechat") "微信" else "QQ"} · ${record.contact}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "提交于 ${readableServerTime(record.createdAt)}\n应用版本 ${record.appVersion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        record.images.forEachIndexed { index, image ->
            FeedbackImage("$admin:${record.id}:${image.id}", "反馈图片 ${index + 1}") {
                controller.loadFeedbackImage(record.id, image.id, admin)
            }
        }
        val processing = record.processing
        if (processing != null) {
            FeedbackProcessingView(
                processing,
                if (admin && record.revision != null && processing.revision != record.revision)
                    "上次处理结果 · 审批版本 ${processing.revision}"
                else "最近处理结果 · 审批版本 ${processing.revision}",
            )
        } else Text("处理结果尚未更新。", style = MaterialTheme.typography.bodySmall)
        val previousResults =
            record.processingHistory.filter { it.revision != processing?.revision }
        if (admin && previousResults.isNotEmpty()) {
            HorizontalDivider()
            Text("处理历史", fontWeight = FontWeight.SemiBold)
            previousResults.forEach { previous ->
                FeedbackProcessingView(previous, "审批版本 ${previous.revision}")
            }
        }
    }
}

@Composable
private fun FeedbackProcessingView(processing: FeedbackProcessing, title: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                when (processing.resultStatus) {
                    "completed" -> "已完成"
                    "needs_input" -> "需要补充信息"
                    "failed" -> "处理失败"
                    else -> processing.resultStatus
                }
            )
            if (processing.resultNote.isNotBlank())
                SelectionContainer { Text(processing.resultNote) }
            Text(
                readableServerTime(processing.processedAt),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FeedbackPagination(page: FeedbackPage, loading: Boolean, onPage: (Int) -> Unit) {
    if (page.total <= page.limit && page.offset == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${if (page.items.isEmpty()) 0 else page.offset + 1}–${page.offset + page.items.size} / ${page.total}",
            style = MaterialTheme.typography.bodySmall,
        )
        ActionRow {
            OutlinedButton(
                onClick = { onPage((page.offset - page.limit).coerceAtLeast(0)) },
                enabled = !loading && page.offset > 0,
            ) {
                Text("上一页")
            }
            OutlinedButton(
                onClick = { onPage(page.offset + page.limit) },
                enabled = !loading && page.offset + page.items.size < page.total,
            ) {
                Text("下一页")
            }
        }
    }
}

@Composable
private fun FeedbackImage(key: String, label: String, load: suspend () -> ByteArray) {
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var retry by remember(key) { mutableIntStateOf(0) }
    var preview by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, retry) {
        error = null
        try {
            val bytes = load()
            bitmap =
                withContext(Dispatchers.IO) {
                    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                            "图片内容无法读取。"
                        }
                        .asImageBitmap()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "图片暂时无法读取。"
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            BitmapPainter(image),
            "$label，点击查看大图",
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp).clickable {
                preview = true
            },
            contentScale = ContentScale.Fit,
        )
        if (preview) ImagePreviewDialog(label, BitmapPainter(image)) { preview = false }
    } else if (error != null) {
        NoticeCard(error.orEmpty())
        TextButton(onClick = { retry++ }) { Text("重新读取图片") }
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("正在读取图片…", style = MaterialTheme.typography.bodySmall)
    }
}

private fun feedbackStatus(status: String) =
    when (status) {
        "pending" -> "待审批"
        "approved" -> "已通过"
        "ignored" -> "已忽略"
        else -> status
    }
