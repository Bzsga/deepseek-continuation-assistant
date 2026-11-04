package app.banzhan.deepseekcontinuation.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.banzhan.deepseekcontinuation.core.ImportedConversation
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.serialization.Serializable

@Serializable @Entity(tableName = "sources")
internal data class SourceRow(@PrimaryKey val id: String, val fileName: String, val bytesBase64: String, val importedAt: Long)

@Serializable @Entity(tableName = "conversations")
internal data class ConversationRow(@PrimaryKey val id: String, val payload: String)

@Serializable @Entity(tableName = "archives")
internal data class ArchiveRow(@PrimaryKey val id: String, val updatedAt: Long, val payload: String)

@Serializable @Entity(tableName = "revisions")
internal data class RevisionRow(@PrimaryKey val id: String, val archiveId: String, val savedAt: Long, val payload: String)

@Serializable @Entity(tableName = "conflicts")
internal data class ConflictRow(@PrimaryKey val id: String, val archiveId: String, val payload: String)

@Serializable @Entity(tableName = "tasks")
internal data class TaskRow(@PrimaryKey val id: String, val archiveId: String, val updatedAt: Long, val payload: String)

@Serializable @Entity(tableName = "chunk_cache")
internal data class ChunkCacheRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "payload_parts", primaryKeys = ["owner", "recordId", "part"])
internal data class PayloadPart(val owner: String, val recordId: String, val part: Int, val content: String)

@Dao
internal abstract class AppDao {
    @Query("SELECT * FROM sources ORDER BY importedAt") protected abstract suspend fun sourceRows(): List<SourceRow>
    @Query("SELECT * FROM conversations") protected abstract suspend fun conversationRows(): List<ConversationRow>
    @Query("SELECT id FROM conversations") abstract suspend fun conversationIds(): List<String>
    @Query("SELECT * FROM conversations WHERE id = :id") protected abstract suspend fun conversationRow(id: String): ConversationRow?
    @Query("SELECT * FROM archives ORDER BY updatedAt DESC") protected abstract suspend fun archiveRows(): List<ArchiveRow>
    @Query("SELECT * FROM archives WHERE id = :id") protected abstract suspend fun archiveRow(id: String): ArchiveRow?
    @Query("SELECT * FROM revisions ORDER BY savedAt DESC") protected abstract suspend fun revisionRows(): List<RevisionRow>
    @Query("SELECT * FROM revisions WHERE archiveId = :id ORDER BY savedAt DESC") protected abstract suspend fun revisionRows(id: String): List<RevisionRow>
    @Query("SELECT * FROM conflicts") protected abstract suspend fun conflictRows(): List<ConflictRow>
    @Query("SELECT * FROM conflicts WHERE archiveId = :id") protected abstract suspend fun conflictRows(id: String): List<ConflictRow>
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC") protected abstract suspend fun taskRows(): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE id = :id") protected abstract suspend fun taskRow(id: String): TaskRow?
    @Query("SELECT * FROM chunk_cache") protected abstract suspend fun cacheRows(): List<ChunkCacheRow>
    @Query("SELECT * FROM chunk_cache WHERE id = :id") protected abstract suspend fun cacheRow(id: String): ChunkCacheRow?
    @Query("SELECT * FROM payload_parts WHERE owner = :owner AND recordId = :id ORDER BY part")
    protected abstract suspend fun parts(owner: String, id: String): List<PayloadPart>
    @Query("SELECT * FROM payload_parts WHERE owner = :owner AND recordId = :id AND part >= :start ORDER BY part LIMIT 8")
    protected abstract suspend fun partsPage(owner: String, id: String, start: Int): List<PayloadPart>

    @Transaction open suspend fun sources(): List<SourceRow> = sourceRows().map { it.copy(bytesBase64 = load("sources", it.id, it.bytesBase64)) }
    @Transaction open suspend fun conversations(): List<ConversationRow> = conversationRows().map { it.copy(payload = load("conversations", it.id, it.payload)) }
    @Transaction open suspend fun conversation(id: String): ConversationRow? = conversationRow(id)?.let { it.copy(payload = load("conversations", it.id, it.payload)) }
    @Transaction open suspend fun readConversation(id: String): ImportedConversation? {
        val row = conversationRow(id) ?: return null
        return withJsonReader(write = { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                val count = PayloadSegments.count(row.payload)
                if (count == null) writer.write(row.payload)
                else {
                    var next = 0
                    while (next < count) {
                        val page = partsPage("conversations", id, next)
                        require(page.isNotEmpty() && next + page.size <= count &&
                            page.withIndex().all { (offset, part) -> part.part == next + offset }) {
                            "本地会话分段不完整，请恢复备份。"
                        }
                        page.forEach { writer.write(it.content) }
                        next += page.size
                    }
                    require(partsPage("conversations", id, count).isEmpty()) { "本地会话分段数量不匹配。" }
                }
            }
        }, read = ConversationStorage::read).also {
            require(it.id == id) { "本地会话编号不匹配。" }
        }
    }

    @Transaction open suspend fun putConversation(conversation: ImportedConversation) {
        deleteParts("conversations", conversation.id)
        val count = withJsonWriter(write = { ConversationStorage.write(conversation, it) }) { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(PayloadSegments.CHARACTERS)
                var part = 0
                while (true) {
                    // Do not split a surrogate pair between SQLite TEXT cells.
                    var used = 0
                    while (used < buffer.size - 1) {
                        val read = reader.read(buffer, used, buffer.size - 1 - used)
                        if (read < 0) break
                        used += read
                    }
                    if (used == 0) break
                    if (used > 0 && buffer[used - 1].isHighSurrogate()) {
                        val next = reader.read()
                        if (next >= 0) buffer[used++] = next.toChar()
                    }
                    require(part < 65_536) { "本地会话分段过多。" }
                    writeParts(listOf(PayloadPart("conversations", conversation.id, part++, String(buffer, 0, used))))
                }
                part
            }
        }
        require(count in 1..65_536) { "本地会话分段数量无效。" }
        write(ConversationRow(conversation.id, PayloadSegments.PREFIX + count))
    }
    @Transaction open suspend fun archives(): List<ArchiveRow> = archiveRows().map { it.copy(payload = load("archives", it.id, it.payload)) }
    @Transaction open suspend fun archive(id: String): ArchiveRow? = archiveRow(id)?.let { it.copy(payload = load("archives", it.id, it.payload)) }
    @Transaction open suspend fun revisions(): List<RevisionRow> = revisionRows().map { it.copy(payload = load("revisions", it.id, it.payload)) }
    @Transaction open suspend fun revisions(id: String): List<RevisionRow> = revisionRows(id).map { it.copy(payload = load("revisions", it.id, it.payload)) }
    @Transaction open suspend fun conflicts(): List<ConflictRow> = conflictRows().map { it.copy(payload = load("conflicts", it.id, it.payload)) }
    @Transaction open suspend fun conflicts(id: String): List<ConflictRow> = conflictRows(id).map { it.copy(payload = load("conflicts", it.id, it.payload)) }
    @Transaction open suspend fun tasks(): List<TaskRow> = taskRows().map { it.copy(payload = load("tasks", it.id, it.payload)) }
    @Transaction open suspend fun task(id: String): TaskRow? = taskRow(id)?.let { it.copy(payload = load("tasks", it.id, it.payload)) }
    @Transaction open suspend fun cache(): List<ChunkCacheRow> = cacheRows().map { it.copy(payload = load("chunk_cache", it.id, it.payload)) }
    @Transaction open suspend fun cache(id: String): ChunkCacheRow? = cacheRow(id)?.let { it.copy(payload = load("chunk_cache", it.id, it.payload)) }

    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: SourceRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: ConversationRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: ArchiveRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: RevisionRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: ConflictRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: TaskRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun write(row: ChunkCacheRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract suspend fun writeParts(parts: List<PayloadPart>)

    @Transaction open suspend fun put(row: SourceRow) = write(row.copy(bytesBase64 = store("sources", row.id, row.bytesBase64)))
    @Transaction open suspend fun put(row: ConversationRow) = write(row.copy(payload = store("conversations", row.id, row.payload)))
    @Transaction open suspend fun put(row: ArchiveRow) = write(row.copy(payload = store("archives", row.id, row.payload)))
    @Transaction open suspend fun put(row: RevisionRow) = write(row.copy(payload = store("revisions", row.id, row.payload)))
    @Transaction open suspend fun put(row: ConflictRow) = write(row.copy(payload = store("conflicts", row.id, row.payload)))
    @Transaction open suspend fun put(row: TaskRow) = write(row.copy(payload = store("tasks", row.id, row.payload)))
    @Transaction open suspend fun put(row: ChunkCacheRow) = write(row.copy(payload = store("chunk_cache", row.id, row.payload)))

    @Query("DELETE FROM payload_parts WHERE owner = :owner AND recordId = :id") protected abstract suspend fun deleteParts(owner: String, id: String)
    @Query("DELETE FROM payload_parts WHERE owner = :owner") protected abstract suspend fun clearParts(owner: String)
    @Query("DELETE FROM archives WHERE id = :id") protected abstract suspend fun deleteArchiveRow(id: String)
    @Query("DELETE FROM revisions WHERE archiveId = :id") protected abstract suspend fun deleteRevisionRows(id: String)
    @Query("DELETE FROM conflicts WHERE archiveId = :id") protected abstract suspend fun deleteConflictRows(id: String)
    @Query("DELETE FROM conflicts WHERE id = :id") protected abstract suspend fun deleteConflictRow(id: String)
    @Query("DELETE FROM tasks WHERE archiveId = :id") protected abstract suspend fun deleteTaskRows(id: String)
    @Query("DELETE FROM tasks WHERE id = :id") protected abstract suspend fun deleteTaskRow(id: String)
    @Query("SELECT id FROM revisions WHERE archiveId = :id") protected abstract suspend fun revisionIds(id: String): List<String>
    @Query("SELECT id FROM conflicts WHERE archiveId = :id") protected abstract suspend fun conflictIds(id: String): List<String>
    @Query("SELECT id FROM tasks WHERE archiveId = :id") protected abstract suspend fun taskIds(id: String): List<String>

    @Transaction open suspend fun deleteArchive(id: String) { deleteParts("archives", id); deleteArchiveRow(id) }
    @Transaction open suspend fun deleteRevisions(id: String) { revisionIds(id).forEach { deleteParts("revisions", it) }; deleteRevisionRows(id) }
    @Transaction open suspend fun deleteConflicts(id: String) { conflictIds(id).forEach { deleteParts("conflicts", it) }; deleteConflictRows(id) }
    @Transaction open suspend fun deleteConflict(id: String) { deleteParts("conflicts", id); deleteConflictRow(id) }
    @Transaction open suspend fun deleteTasks(id: String) { taskIds(id).forEach { deleteParts("tasks", it) }; deleteTaskRows(id) }
    @Transaction open suspend fun deleteTask(id: String) { deleteParts("tasks", id); deleteTaskRow(id) }

    @Query("DELETE FROM sources") protected abstract suspend fun clearSourceRows()
    @Query("DELETE FROM conversations") protected abstract suspend fun clearConversationRows()
    @Query("DELETE FROM archives") protected abstract suspend fun clearArchiveRows()
    @Query("DELETE FROM revisions") protected abstract suspend fun clearRevisionRows()
    @Query("DELETE FROM conflicts") protected abstract suspend fun clearConflictRows()
    @Query("DELETE FROM tasks") protected abstract suspend fun clearTaskRows()
    @Query("DELETE FROM chunk_cache") protected abstract suspend fun clearCacheRows()

    @Transaction open suspend fun clearSources() { clearParts("sources"); clearSourceRows() }
    @Transaction open suspend fun clearConversations() { clearParts("conversations"); clearConversationRows() }
    @Transaction open suspend fun clearArchives() { clearParts("archives"); clearArchiveRows() }
    @Transaction open suspend fun clearRevisions() { clearParts("revisions"); clearRevisionRows() }
    @Transaction open suspend fun clearConflicts() { clearParts("conflicts"); clearConflictRows() }
    @Transaction open suspend fun clearTasks() { clearParts("tasks"); clearTaskRows() }
    @Transaction open suspend fun clearCache() { clearParts("chunk_cache"); clearCacheRows() }

    private suspend fun load(owner: String, id: String, value: String): String {
        if (PayloadSegments.count(value) == null) return value
        return PayloadSegments.join(value, parts(owner, id))
    }

    private suspend fun store(owner: String, id: String, value: String): String {
        deleteParts(owner, id)
        if (value.length <= PayloadSegments.CHARACTERS) return value
        val pieces = PayloadSegments.split(value)
        writeParts(pieces.mapIndexed { index, content -> PayloadPart(owner, id, index, content) })
        return PayloadSegments.PREFIX + pieces.size
    }
}

@Database(
    entities = [SourceRow::class, ConversationRow::class, ArchiveRow::class, RevisionRow::class,
        ConflictRow::class, TaskRow::class, ChunkCacheRow::class, PayloadPart::class], version = 2, exportSchema = false,
)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): AppDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS payload_parts (owner TEXT NOT NULL, recordId TEXT NOT NULL, part INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY(owner, recordId, part))")
                val columns = listOf("sources" to "bytesBase64", "conversations" to "payload", "archives" to "payload",
                    "revisions" to "payload", "conflicts" to "payload", "tasks" to "payload", "chunk_cache" to "payload")
                columns.forEach { (table, column) ->
                    val records = mutableListOf<Pair<String, Long>>()
                    database.query("SELECT id, length($column) FROM $table WHERE length($column) > ?",
                        arrayOf<Any>(PayloadSegments.CHARACTERS)).use { cursor ->
                        while (cursor.moveToNext()) records += cursor.getString(0) to cursor.getLong(1)
                    }
                    records.forEach { (id, length) ->
                        val count = ((length + PayloadSegments.CHARACTERS - 1) / PayloadSegments.CHARACTERS).toInt()
                        repeat(count) { part ->
                            // SQL substr bounds the cell before Android creates a CursorWindow.
                            database.execSQL("INSERT INTO payload_parts(owner, recordId, part, content) SELECT ?, id, ?, substr($column, ?, ?) FROM $table WHERE id = ?",
                                arrayOf<Any>(table, part, part.toLong() * PayloadSegments.CHARACTERS + 1, PayloadSegments.CHARACTERS, id))
                        }
                        database.execSQL("UPDATE $table SET $column = ? WHERE id = ?", arrayOf<Any>(PayloadSegments.PREFIX + count, id))
                    }
                }
            }
        }
    }
}
