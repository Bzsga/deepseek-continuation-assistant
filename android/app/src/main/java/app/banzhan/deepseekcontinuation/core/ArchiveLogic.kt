package app.banzhan.deepseekcontinuation.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ArchiveLogic {
    private const val LOCAL_EXCERPT_CHARACTERS = 1600

    fun chunks(messages: List<ChatMessage>, maxCharacters: Int = 6000): List<SummaryChunk> {
        require(maxCharacters >= 2) { "分段上限至少为 2 个字符，才能完整保存 Unicode 字符。" }
        val result = mutableListOf<SummaryChunk>()
        val pending = mutableListOf<ChatMessage>()
        val identities = mutableListOf<String>()
        val occurrences = mutableMapOf<String, Int>()
        var characters = 0

        fun flush() {
            if (pending.isEmpty()) return
            result += SummaryChunk(hash("chunk-v1", *identities.toTypedArray()), pending.toList())
            pending.clear()
            identities.clear()
            characters = 0
        }

        for (message in messages) {
            val occurrence = occurrences.getOrDefault(message.id, 0)
            occurrences[message.id] = occurrence + 1
            var offset = 0
            do {
                if (characters == maxCharacters) flush()
                var end = safeEnd(message.content, offset, maxCharacters - characters)
                if (end == offset && offset < message.content.length) {
                    flush()
                    end = safeEnd(message.content, offset, maxCharacters)
                }
                val part = message.copy(content = message.content.substring(offset, end))
                pending += part
                // Offsets distinguish identical repeated segments without changing their source ID.
                identities += hash(
                    "part-v1", message.id, occurrence.toString(), offset.toString(),
                    part.role, part.content, part.timestamp, *part.attachments.toTypedArray(),
                )
                characters += part.content.length
                offset = end
            } while (offset < message.content.length)
        }
        flush()
        return result
    }

    fun localEntries(messages: List<ChatMessage>): List<ArchiveEntry> {
        return messages.flatMap { message ->
            val content = message.content
            if (content.isBlank() && message.attachments.isEmpty()) return@flatMap emptyList()
            val kind = when (message.role.lowercase()) {
                "user" -> EntryKind.USER_FACT
                "assistant" -> EntryKind.ASSISTANT_SUGGESTION
                else -> EntryKind.UNCONFIRMED
            }
            val label = when (message.role.lowercase()) {
                "user" -> "用户原文"
                "assistant" -> "助手原文"
                "system" -> "系统原文（上下文）"
                "tool" -> "工具原文（待核验）"
                else -> "${message.role}原文（角色待核验）"
            }
            val parts = split(content, LOCAL_EXCERPT_CHARACTERS)
            val sourceHash = hash("source-v1", message.id, message.role, content, *message.attachments.toTypedArray())
            parts.mapIndexed { index, part ->
                val attachmentText = if (index == 0 && message.attachments.isNotEmpty()) {
                    "\n附件元数据（未读取附件正文）\n" + message.attachments.joinToString("\n")
                } else {
                    ""
                }
                val position = if (parts.size > 1) " · ${index + 1}/${parts.size}" else ""
                ArchiveEntry(
                    id = hash("excerpt-v1", sourceHash, index.toString()),
                    category = EntryCategory.QUOTES,
                    text = "【$label$position】\n$part$attachmentText",
                    sourceIds = listOf(message.id),
                    kind = kind,
                )
            }
        }.distinctBy { it.id }
    }

    fun merge(existing: List<ArchiveEntry>, incoming: List<ArchiveEntry>): MergeResult {
        val entries = linkedMapOf<String, ArchiveEntry>()
        val conflicts = mutableListOf<EntryConflict>()

        for (entry in existing) {
            val previous = entries[entry.id]
            if (previous == null) {
                entries[entry.id] = entry
            } else {
                // Recover duplicate stored IDs conservatively, giving saved user state priority.
                val winner = if (protection(entry) > protection(previous)) entry else previous
                val other = if (winner === entry) previous else entry
                entries[entry.id] = winner
                if (!sameContent(winner, other)) conflicts += EntryConflict(winner, other)
            }
        }
        for (proposed in incoming) {
            val current = entries[proposed.id]
            if (current == null) {
                entries[proposed.id] = proposed
            } else if (!sameContent(current, proposed)) {
                // Applying a proposal is a separate, explicit conflict-resolution operation.
                conflicts += EntryConflict(current, proposed)
            }
        }
        return MergeResult(entries.values.toList(), conflicts.distinct())
    }

    fun continuationExport(archive: Archive): ExportResult {
        val entries = archive.entries.filter { !it.deleted && it.text.isNotBlank() }
        val background = archive.background.takeIf { it.isNotBlank() }
        require(entries.isNotEmpty() || background != null) { "没有可用于续聊的内容，请先添加聊天内容或背景。" }

        val contentLabel = when (archive.generationMode) {
            "original" -> "聊天原文"
            "summary" -> "续聊摘要"
            else -> "已保存的续聊内容"
        }
        val sections = listOf(
            EntryCategory.BACKGROUND to "关于我",
            EntryCategory.PEOPLE to "重要的人和事",
            EntryCategory.DECISIONS to "已确定的事",
            EntryCategory.RECENT to "接下来要聊的事",
            EntryCategory.QUOTES to "重要原话",
        )
        val text = buildString {
            append("请沿用下面的背景、讨论过的观点和最近进展，继续与我交流，并以我后续的纠正为准。")
            append("仅在出现真实矛盾、涉及重要决定或信息明确未定时向我核实。")
            append("讨论中的建议和理解沿用原有语气，个人经历、病史、承诺和已采取的行动以我的明确陈述为依据。\n\n")
            append("【$contentLabel】${archive.title}\n")
            if (archive.generationMode == "original") {
                append("聊天原文仅包含我所选的内容。\n")
            }
            for ((category, title) in sections) {
                val sectionEntries = entries.filter { it.category == category }
                val sectionBackground = background.takeIf { category == EntryCategory.BACKGROUND }
                if (sectionEntries.isEmpty() && sectionBackground == null) continue
                append("\n## $title\n")
                if (sectionBackground != null) {
                    append("\n【用户补充】\n$sectionBackground\n")
                }
                var previousKind: EntryKind? = null
                for (entry in sectionEntries) {
                    if (entry.kind != previousKind) {
                        append("\n【${continuationKindLabel(entry.kind)}】\n")
                        previousKind = entry.kind
                    }
                    append("${entry.text}\n\n")
                }
            }
        }
        return ExportResult(text, omittedCount = 0, pinnedOverflow = false)
    }

    fun continuationExport(archive: Archive, conversations: List<ImportedConversation>): ExportResult =
        ContinuityLogic.export(archive, conversations)

    fun export(archive: Archive, maxCharacters: Int = 6000): ExportResult {
        require(maxCharacters >= 100) { "导出上限至少为 100 个字符。" }
        val entries = archive.entries.filter { !it.deleted && it.text.isNotBlank() }
        val background = archive.background.takeIf { it.isNotBlank() }
        require(entries.isNotEmpty() || background != null) { "档案为空，请先添加对话片段或背景。" }

        val title = prefix(archive.title, minOf(80, maxCharacters / 5))
        val header = "【续聊档案】$title\n沿用讨论背景与原有语气，以后续纠正为准。\n"
        val maximumNotice = notice(entries.size, true)
        var remaining = maxCharacters - header.length - maximumNotice.length
        val selected = mutableListOf<String>()
        var omitted = 0
        var pinnedOverflow = false

        for (entry in entries.filter { it.pinned }) {
            val block = render(entry)
            if (block.length <= remaining) {
                selected += block
                remaining -= block.length
            } else {
                omitted++
                pinnedOverflow = true
            }
        }
        if (background != null) {
            val block = "\n【用户补充背景】\n$background\n"
            if (block.length <= remaining) {
                selected.add(0, block)
                remaining -= block.length
            } else {
                pinnedOverflow = true
            }
        }
        for (entry in entries.filterNot { it.pinned }) {
            val block = render(entry)
            if (block.length <= remaining) {
                selected += block
                remaining -= block.length
            } else {
                omitted++
            }
        }
        val text = header + selected.joinToString("") + notice(omitted, pinnedOverflow)
        check(text.length <= maxCharacters) { "导出长度计算异常。" }
        return ExportResult(text, omitted, pinnedOverflow)
    }

    private fun render(entry: ArchiveEntry): String {
        val category = when (entry.category) {
            EntryCategory.BACKGROUND -> "背景"
            EntryCategory.PEOPLE -> "人物"
            EntryCategory.DECISIONS -> "决定"
            EntryCategory.RECENT -> "近期"
            EntryCategory.QUOTES -> "原文摘录"
        }
        val kind = continuationKindLabel(entry.kind)
        val pinned = if (entry.pinned) " · 置顶" else ""
        val sources = if (entry.sourceIds.isEmpty()) "手工补充" else entry.sourceIds.distinct().joinToString("、")
        return "\n【$category · $kind$pinned】\n${entry.text}\n来源 $sources\n"
    }

    private fun continuationKindLabel(kind: EntryKind): String = when (kind) {
        EntryKind.USER_FACT -> "用户陈述"
        EntryKind.ASSISTANT_SUGGESTION -> "讨论中的建议"
        EntryKind.UNCONFIRMED -> "讨论与理解"
    }

    private fun notice(omitted: Int, overflow: Boolean): String {
        if (omitted == 0 && !overflow) return ""
        val missing = if (omitted > 0) "本次省略 $omitted 条。" else ""
        val important = if (overflow) "置顶条目或背景超出上限，未完整纳入，请精简后导出。" else ""
        return "\n【导出提示】$missing$important\n"
    }

    private fun sameContent(a: ArchiveEntry, b: ArchiveEntry): Boolean =
        a.category == b.category && a.text == b.text && a.kind == b.kind &&
            a.sourceIds.toSet() == b.sourceIds.toSet()

    private fun protection(entry: ArchiveEntry): Int =
        (if (entry.deleted) 4 else 0) + (if (entry.edited) 2 else 0) + (if (entry.pinned) 1 else 0)

    private fun split(text: String, maxCharacters: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val end = safeEnd(text, offset, maxCharacters)
            result += text.substring(offset, end)
            offset = end
        }
        return result
    }

    private fun safeEnd(text: String, start: Int, length: Int): Int {
        var end = minOf(text.length, start + length)
        if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return end
    }

    private fun prefix(text: String, maxCharacters: Int): String {
        if (text.length <= maxCharacters) return text
        return text.substring(0, safeEnd(text, 0, maxCharacters - 1)) + "…"
    }

    private fun hash(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
