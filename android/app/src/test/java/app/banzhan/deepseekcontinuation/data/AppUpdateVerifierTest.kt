package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.AppUpdateManifest
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signatureA = "a".repeat(64)
    private val signatureB = "b".repeat(64)
    private val installed = AppUpdatePackageIdentity("app.banzhan.deepseekcontinuation", 6L, "preview.6", 26, setOf(signatureA))
    private val candidate = installed.copy(versionCode = 7L, versionName = "preview.7")
    private val bytes = "synthetic APK bytes".toByteArray()

    @Test
    fun exactIdentityAndCurrentSignerSetAcceptANewerVersion() {
        validateAppUpdateIdentity(manifest(), candidate, installed, 26)
        val twoSigners = installed.copy(signerSha256 = linkedSetOf(signatureA, signatureB))
        validateAppUpdateIdentity(manifest(), candidate.copy(signerSha256 = linkedSetOf(signatureB, signatureA)), twoSigners, 35)
    }

    @Test
    fun aDifferentOrHistoricalSigningCertificateCannotReplaceTheCurrentSigner() {
        listOf(emptySet(), setOf(signatureB), setOf(signatureA, signatureB)).forEach { signers ->
            assertThrows(AppUpdateVerificationException::class.java) {
                validateAppUpdateIdentity(manifest(), candidate.copy(signerSha256 = signers), installed, 35)
            }
        }
        assertThrows(AppUpdateVerificationException::class.java) {
            validateAppUpdateIdentity(manifest(), candidate, installed.copy(signerSha256 = emptySet()), 35)
        }
    }

    @Test
    fun exactPackageVersionAndMinimumSdkMetadataAreRequired() {
        listOf(candidate.copy(packageName = "other.application"), candidate.copy(versionCode = 8L),
            candidate.copy(versionName = "different"), candidate.copy(minSdk = 27)).forEach { invalid ->
            assertThrows(AppUpdateVerificationException::class.java) { validateAppUpdateIdentity(manifest(), invalid, installed, 35) }
        }
        assertThrows(AppUpdateVerificationException::class.java) {
            validateAppUpdateIdentity(manifest().copy(packageName = "other.application"), candidate, installed, 35)
        }
        assertThrows(AppUpdateVerificationException::class.java) {
            validateAppUpdateIdentity(manifest().copy(minSdk = 36), candidate.copy(minSdk = 36), installed, 35)
        }
    }

    @Test
    fun installedVersionsAreReadAsLongAndNeverAllowReinstallationOrDowngrade() {
        listOf(7L, 8L, Int.MAX_VALUE.toLong() + 1L).forEach { current ->
            assertThrows(AppUpdateVerificationException::class.java) {
                validateAppUpdateIdentity(manifest(), candidate, installed.copy(versionCode = current), 35)
            }
        }
    }

    @Test
    fun matchingPrivateFileVerifiesAgainAndDetectsSameLengthTampering() {
        val cache = temporary.newFolder("cache")
        val file = updateFile(cache)
        assertEquals(file.canonicalFile, verifyAppUpdateFile(file, cache, manifest()))
        file.writeBytes(ByteArray(bytes.size) { 1 })
        assertThrows(AppUpdateVerificationException::class.java) { verifyAppUpdateFile(file, cache, manifest()) }
    }

    @Test
    fun partialFilesOutsideFilesNestedPathsAndMissingFilesAreRejected() {
        val cache = temporary.newFolder("cache")
        val valid = updateFile(cache)
        val partial = File(valid.parentFile, "release.apk.part").apply { writeBytes(bytes) }
        val outside = File(cache, "outside.apk").apply { writeBytes(bytes) }
        val nested = File(valid.parentFile, "nested").apply { mkdirs() }.resolve("release.apk").apply { writeBytes(bytes) }
        val traversed = File(valid.parentFile, "../outside.apk")
        val missing = File(valid.parentFile, "missing.apk")
        listOf(partial, outside, nested, traversed, missing).forEach { invalid ->
            val error = assertThrows(AppUpdateVerificationException::class.java) { verifyAppUpdateFile(invalid, cache, manifest()) }
            assertFalse(error.message.orEmpty().contains(cache.absolutePath))
        }
    }

    @Test
    fun incorrectSizeHashAndOversizedMetadataAreRejectedBeforeInstallation() {
        val cache = temporary.newFolder("cache")
        val file = updateFile(cache)
        listOf(manifest().copy(apkSize = bytes.size + 1L), manifest().copy(apkSize = 64L * 1024 * 1024 + 1),
            manifest().copy(sha256 = "0".repeat(64)), manifest().copy(sha256 = "invalid")).forEach { invalid ->
            assertThrows(AppUpdateVerificationException::class.java) { verifyAppUpdateFile(file, cache, invalid) }
        }
    }

    private fun updateFile(cache: File): File = File(cache, "app-updates").apply { mkdirs() }
        .resolve("release.apk").apply { writeBytes(bytes) }

    private fun manifest() = AppUpdateManifest(
        schemaVersion = 1,
        packageName = installed.packageName,
        versionCode = 7,
        versionName = "preview.7",
        apkUrl = "https://digimonlegend.com/continuation-updates/apks/preview.7.apk",
        apkSize = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) },
        minSdk = 26,
        releasedAt = "2026-11-04T00:00:00Z",
        changelog = emptyList(),
    )
}
