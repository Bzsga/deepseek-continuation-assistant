package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import app.banzhan.deepseekcontinuation.core.AppUpdateManifestParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun repeatedStartAndRecreatedObserverKeepTheSameSystemTask() {
        val fixture = fixture()
        val download = fixture.manager.start(manifest())
        assertEquals(download, fixture.manager.start(manifest()))
        val recreated = PersistentAppUpdateDownloads(fixture.platform, fixture.store, fixture.parts) { SECOND_TOKEN }
        assertEquals(download, recreated.restore())
        assertEquals(download, recreated.start(manifest()))
        assertEquals(1, fixture.platform.enqueued)
    }

    @Test
    fun enqueueSuccessBeforeIdPersistenceIsRecoveredWithoutAnotherRequest() {
        val fixture = fixture()
        fixture.store.failOnSave = 2
        expectFailure { fixture.manager.start(manifest()) }
        assertEquals(1, fixture.platform.enqueued)
        assertEquals(0L, fixture.store.value!!.id)

        val recreated = PersistentAppUpdateDownloads(fixture.platform, fixture.store, fixture.parts) { SECOND_TOKEN }
        val restored = recreated.restore()!!
        assertTrue(restored.id > 0)
        assertEquals(FIRST_TOKEN, restored.token)
        assertEquals(restored, recreated.start(manifest()))
        assertEquals(restored, fixture.store.value)
        assertEquals(1, fixture.platform.enqueued)
    }

    @Test
    fun unmatchedIntentIsReportedMissingAndIsNeverAutomaticallyEnqueued() {
        val fixture = fixture()
        val intent = AppUpdateDownload(manifest(), 0, FIRST_TOKEN)
        fixture.store.value = intent
        assertEquals(intent, fixture.manager.restore())
        assertEquals(AppUpdateDownloadStage.MISSING, fixture.manager.progress(intent).stage)
        assertEquals(0, fixture.platform.enqueued)
        assertEquals(intent, fixture.store.value)
    }

    @Test
    fun cancellationAndLateCleanupCannotClearANewerTask() {
        val fixture = fixture()
        val first = fixture.manager.start(manifest())
        fixture.manager.remove(first)
        assertNull(fixture.store.value)
        val second = fixture.manager.start(manifest())
        fixture.manager.remove(first)
        assertEquals(second, fixture.store.value)
        assertNotNull(fixture.platform.rows[second.id])
        assertEquals(listOf(first.id), fixture.platform.removed)
        assertEquals(2, fixture.platform.enqueued)
    }

    @Test
    fun staleIdentityCannotRemoveARowWithTheSameIdAndAnotherToken() {
        val fixture = fixture()
        val first = fixture.manager.start(manifest())
        val replacement = first.copy(token = SECOND_TOKEN)
        fixture.store.value = replacement
        fixture.platform.rows[first.id] = fixture.platform.rows.getValue(first.id).copy(token = SECOND_TOKEN)
        fixture.manager.remove(first)
        assertEquals(replacement, fixture.store.value)
        assertEquals(SECOND_TOKEN, fixture.platform.rows[first.id]!!.token)
        assertTrue(fixture.platform.removed.isEmpty())
    }

    @Test
    fun completedBytesAreCopiedExactlyAndRemainRecoverableUntilVerification() {
        val fixture = fixture()
        val download = fixture.completed(byteArrayOf(1, 2, 3, 4))
        val target = File(fixture.parts, "verified-next.part")
        fixture.manager.copyTo(download, target)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
        assertEquals(download, fixture.store.value)
        assertNotNull(fixture.platform.rows[download.id])
        assertTrue(fixture.platform.removed.isEmpty())
    }

    @Test
    fun truncatedAndOversizedCopiesDeleteOnlyThePartialCopyAndKeepTheSystemTask() {
        val fixture = fixture()
        val download = fixture.completed(byteArrayOf(1, 2, 3, 4))
        listOf(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4, 5)).forEachIndexed { index, bytes ->
            fixture.platform.contents[download.id] = bytes
            val target = File(fixture.parts, "failed-$index.part")
            expectFailure { fixture.manager.copyTo(download, target) }
            assertFalse(target.exists())
            assertEquals(download, fixture.store.value)
            assertNotNull(fixture.platform.rows[download.id])
        }
        assertTrue(fixture.platform.removed.isEmpty())
    }

    @Test
    fun copiesCannotOverwriteOutsideThePrivatePartsDirectoryOrAnApk() {
        val fixture = fixture()
        val download = fixture.completed(byteArrayOf(1, 2, 3, 4))
        val outside = File(temporary.root, "outside.part").apply { writeText("retain outside") }
        val wrongExtension = File(fixture.parts, "existing.apk").apply { writeText("retain apk") }
        listOf(outside, wrongExtension).forEach { target ->
            val original = target.readBytes()
            expectFailure { fixture.manager.copyTo(download, target) }
            assertArrayEquals(original, target.readBytes())
        }
        assertEquals(0, fixture.platform.opened)
        assertEquals(download, fixture.store.value)
    }

    @Test
    fun waitingForNetworkKeepsProgressAndDoesNotCreateAnotherDownload() {
        val fixture = fixture()
        val download = fixture.manager.start(manifest())
        fixture.platform.rows[download.id] = fixture.platform.rows.getValue(download.id).copy(
            token = null, stage = AppUpdateDownloadStage.WAITING, downloadedBytes = 2, totalBytes = -1,
            message = "正在等待网络，连接后系统会继续下载。", filePresent = false, destinationPending = true,
        )
        val progress = fixture.manager.progress(download)
        assertEquals(AppUpdateDownloadStage.WAITING, progress.stage)
        assertEquals(2L, progress.downloadedBytes)
        assertEquals(4L, progress.totalBytes)
        assertTrue(progress.message!!.contains("等待网络"))
        assertEquals(download, fixture.manager.start(manifest()))
        assertEquals(1, fixture.platform.enqueued)
        expectFailure { fixture.manager.copyTo(download, File(fixture.parts, "waiting.part")) }
        assertEquals(0, fixture.platform.opened)
    }

    @Test
    fun mismatchedUrlOrDestinationCannotBecomeACompletedCandidate() {
        val fixture = fixture()
        val download = fixture.completed(byteArrayOf(1, 2, 3, 4))
        val original = fixture.platform.rows.getValue(download.id)
        listOf(original.copy(url = "https://example.invalid/unrelated.apk"), original.copy(token = SECOND_TOKEN)).forEachIndexed { index, row ->
            fixture.platform.rows[download.id] = row
            assertEquals(AppUpdateDownloadStage.MISSING, fixture.manager.progress(download).stage)
            expectFailure { fixture.manager.copyTo(download, File(fixture.parts, "unrelated-$index.part")) }
        }
        assertEquals(0, fixture.platform.opened)
        assertEquals(download, fixture.store.value)
    }

    @Test
    fun ambiguousIntentDoesNotPickOneRowOrEnqueueAgain() {
        val fixture = fixture()
        fixture.store.value = AppUpdateDownload(manifest(), 0, FIRST_TOKEN)
        fixture.platform.rows[10] = row(10, FIRST_TOKEN)
        fixture.platform.rows[11] = row(11, FIRST_TOKEN)
        expectFailure { fixture.manager.restore() }
        assertEquals(0, fixture.platform.enqueued)
        assertEquals(0L, fixture.store.value!!.id)
    }

    @Test
    fun failedAndMissingFilesRequireAnExplicitNewStart() {
        val fixture = fixture()
        val first = fixture.manager.start(manifest())
        val original = fixture.platform.rows.getValue(first.id)
        fixture.platform.rows[first.id] = original.copy(stage = AppUpdateDownloadStage.FAILED,
            message = "系统下载未完成，请重新下载。")
        assertEquals(AppUpdateDownloadStage.FAILED, fixture.manager.progress(first).stage)
        fixture.platform.rows[first.id] = original.copy(stage = AppUpdateDownloadStage.COMPLETED, filePresent = false)
        assertEquals(AppUpdateDownloadStage.MISSING, fixture.manager.progress(first).stage)
        assertEquals(1, fixture.platform.enqueued)

        val second = fixture.manager.start(manifest())
        assertNotEquals(first.id, second.id)
        assertEquals(SECOND_TOKEN, second.token)
        assertEquals(second, fixture.store.value)
        assertEquals(listOf(first.id), fixture.platform.removed)
    }

    private fun fixture(): Fixture {
        val platform = FakePlatform()
        val store = FakeStore()
        val parts = temporary.newFolder("parts")
        val tokens = listOf(FIRST_TOKEN, SECOND_TOKEN).iterator()
        val manager = PersistentAppUpdateDownloads(platform, store, parts) { tokens.next() }
        return Fixture(platform, store, parts, manager)
    }

    private data class Fixture(
        val platform: FakePlatform,
        val store: FakeStore,
        val parts: File,
        val manager: PersistentAppUpdateDownloads,
    ) {
        fun completed(bytes: ByteArray): AppUpdateDownload = manager.start(manifest()).also {
            platform.rows[it.id] = platform.rows.getValue(it.id).copy(stage = AppUpdateDownloadStage.COMPLETED,
                downloadedBytes = 4, totalBytes = 4, filePresent = true)
            platform.contents[it.id] = bytes
        }
    }

    private class FakeStore : AppUpdateDownloadStore {
        var value: AppUpdateDownload? = null
        var saves = 0
        var failOnSave: Int? = null
        override fun load(): AppUpdateDownload? = value
        override fun save(download: AppUpdateDownload) {
            saves++
            if (saves == failOnSave) throw IOException("synthetic storage interruption")
            value = download
        }
        override fun clear() { value = null }
    }

    private class FakePlatform : AppUpdateDownloadPlatform {
        val rows = linkedMapOf<Long, AppUpdatePlatformDownload>()
        val contents = mutableMapOf<Long, ByteArray>()
        val removed = mutableListOf<Long>()
        val discarded = mutableListOf<String>()
        var enqueued = 0
        var opened = 0
        private var nextId = 1L
        override fun enqueue(manifest: AppUpdateManifest, token: String): Long {
            val id = nextId++
            enqueued++
            rows[id] = row(id, token).copy(url = manifest.apkUrl, totalBytes = manifest.apkSize)
            return id
        }
        override fun find(manifest: AppUpdateManifest, token: String): List<AppUpdatePlatformDownload> =
            rows.values.filter { it.url == manifest.apkUrl && it.token == token }
        override fun query(id: Long): AppUpdatePlatformDownload? = rows[id]
        override fun open(id: Long): InputStream {
            opened++
            return ByteArrayInputStream(contents[id] ?: throw IOException("synthetic missing file"))
        }
        override fun remove(id: Long) { rows.remove(id); contents.remove(id); removed += id }
        override fun discard(token: String) { discarded += token }
    }

    companion object {
        private const val FIRST_TOKEN = "11111111-1111-4111-8111-111111111111"
        private const val SECOND_TOKEN = "22222222-2222-4222-8222-222222222222"
        private fun manifest(): AppUpdateManifest = AppUpdateManifestParser.parse(
            """{"schemaVersion":1,"packageName":"app.banzhan.deepseekcontinuation","versionCode":20,"versionName":"1.0.11","apkUrl":"https://digimonlegend.com/continuation-updates/apks/20/synthetic.apk","apkSize":4,"sha256":"${"a".repeat(64)}","minSdk":26,"releasedAt":"2026-11-05T00:00:00Z","changelog":[]}""",
        )
        private fun row(id: Long, token: String) = AppUpdatePlatformDownload(id, manifest().apkUrl, token,
            AppUpdateDownloadStage.RUNNING, totalBytes = 4)
        private fun expectFailure(block: () -> Any?) {
            assertThrows(AppUpdateDownloadException::class.java) { block() }
        }
    }
}
