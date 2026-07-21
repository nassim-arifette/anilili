package com.miruronative.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleasePolicyTest {
    @Test
    fun `parses a semantic version from the release title or tag`() {
        assertEquals("0.2.0", UpdateReleasePolicy.parseVersion("AniLili+ v0.2.0"))
        assertEquals("1.4.12", UpdateReleasePolicy.parseVersion("v1.4.12"))
        assertNull(UpdateReleasePolicy.parseVersion("APK-release"))
        assertEquals("0.2.0", UpdateReleasePolicy.parseReleaseTag("v0.2.0"))
        assertNull(UpdateReleasePolicy.parseReleaseTag("0.2.0"))
        assertNull(UpdateReleasePolicy.parseReleaseTag("v0.2.1-rc1"))
    }

    @Test
    fun `compares numeric version components without lexical ordering bugs`() {
        assertTrue(UpdateReleasePolicy.isNewer("0.2.10", "0.2.9"))
        assertTrue(UpdateReleasePolicy.isNewer("1.0.0", "0.9.99-debug"))
        assertFalse(UpdateReleasePolicy.isNewer("0.2.0", "0.2.0-debug"))
        assertFalse(UpdateReleasePolicy.isNewer("0.1.99", "0.2.0"))
        assertFalse(UpdateReleasePolicy.isNewer("not-a-version", "0.2.0"))
    }

    @Test
    fun `selects the branded release apk and rejects debug artifacts`() {
        assertEquals(
            "anilili-plus-v0.2.0.apk",
            UpdateReleasePolicy.selectApkAsset(
                listOf("checksums.txt", "app-debug.apk", "anilili-plus-v0.2.0.apk"),
                "0.2.0",
            ),
        )
        assertNull(UpdateReleasePolicy.selectApkAsset(listOf("release.apk"), "0.2.0"))
        assertNull(UpdateReleasePolicy.selectApkAsset(listOf("app-debug.apk", "app.aab"), "0.2.0"))
        assertNull(UpdateReleasePolicy.selectApkAsset(listOf("one.apk", "two.apk"), "0.2.0"))
        assertNull(
            UpdateReleasePolicy.selectApkAsset(
                listOf("anilili-plus-v0.2.0.apk", "anilili-plus-v0.2.0.apk"),
                "0.2.0",
            ),
        )
    }

    @Test
    fun `accepts only the fork GitHub release download path`() {
        val asset = "anilili-plus-v0.2.0.apk"
        assertTrue(
            UpdateReleasePolicy.isAllowedDownloadUrl(
                "https://github.com/nassim-arifette/anilili/releases/download/v0.2.0/$asset",
                asset,
            ),
        )
        assertFalse(
            UpdateReleasePolicy.isAllowedDownloadUrl(
                "https://example.com/nassim-arifette/anilili/releases/download/v0.2.0/$asset",
                asset,
            ),
        )
        assertFalse(
            UpdateReleasePolicy.isAllowedDownloadUrl(
                "http://github.com/nassim-arifette/anilili/releases/download/v0.2.0/$asset",
                asset,
            ),
        )
    }

    @Test
    fun `parses only a complete GitHub sha256 digest`() {
        val hash = "a".repeat(64)
        assertEquals(hash, UpdateReleasePolicy.parseSha256Digest("sha256:$hash"))
        assertEquals(hash, UpdateReleasePolicy.parseSha256Digest("SHA256:${hash.uppercase()}"))
        assertNull(UpdateReleasePolicy.parseSha256Digest(hash))
        assertNull(UpdateReleasePolicy.parseSha256Digest("sha256:abcd"))
    }

    @Test
    fun `requires package version code and signer continuity`() {
        val current = identity(versionName = "0.2.0", versionCode = 29, signer = "cert-a")
        assertNull(
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(versionName = "0.2.1", versionCode = 30, signer = "cert-a"),
                "0.2.1",
            ),
        )
        assertEquals(
            "Downloaded APK belongs to a different application",
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(packageName = "example.other", versionName = "0.2.1", versionCode = 30),
                "0.2.1",
            ),
        )
        assertEquals(
            "Downloaded APK does not have a newer Android version code",
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(versionName = "0.2.1", versionCode = 29),
                "0.2.1",
            ),
        )
        assertEquals(
            "Downloaded APK was signed with a different certificate",
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(versionName = "0.2.1", versionCode = 30, signer = "cert-b"),
                "0.2.1",
            ),
        )
        assertEquals(
            "Downloaded APK version does not match the GitHub release",
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(versionName = "0.2.2", versionCode = 30),
                "0.2.1",
            ),
        )
        assertEquals(
            "Could not verify the APK signing certificate",
            UpdateReleasePolicy.validateCandidate(
                current.copy(signerSha256 = emptySet()),
                identity(versionName = "0.2.1", versionCode = 30),
                "0.2.1",
            ),
        )
        assertEquals(
            "Downloaded APK was signed with a different certificate",
            UpdateReleasePolicy.validateCandidate(
                current,
                identity(versionName = "0.2.1", versionCode = 30).copy(
                    signerSha256 = setOf("cert-a", "cert-b"),
                ),
                "0.2.1",
            ),
        )
    }

    private fun identity(
        packageName: String = "com.nassimarifette.anililiplus",
        versionName: String = "0.2.0",
        versionCode: Long = 29,
        signer: String = "cert-a",
    ) = UpdateReleasePolicy.ApkIdentity(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        signerSha256 = setOf(signer),
    )
}
