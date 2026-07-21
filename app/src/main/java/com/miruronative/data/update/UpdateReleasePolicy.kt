package com.miruronative.data.update

import java.net.URI

/** Pure release parsing rules kept separate from Android and networking for unit tests. */
internal object UpdateReleasePolicy {
    private const val PREFERRED_APK_PREFIX = "anilili-plus"
    const val MAX_APK_BYTES = 250L * 1024L * 1024L

    fun parseVersion(text: String): String? =
        Regex("""(?i)(?:^|\D)v?(\d+(?:\.\d+)+)(?:\D|$)""")
            .find(text)
            ?.groupValues
            ?.get(1)

    fun parseReleaseTag(tag: String): String? =
        Regex("""^v(\d+\.\d+\.\d+)$""")
            .matchEntire(tag)
            ?.groupValues
            ?.get(1)

    fun parseSha256Digest(text: String): String? =
        Regex("""(?i)^sha256:([0-9a-f]{64})$""")
            .matchEntire(text.trim())
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = numericVersionParts(remote)
        val currentParts = numericVersionParts(current)
        if (remoteParts.isEmpty() || currentParts.isEmpty()) return false

        for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    /** Accepts one exact immutable-version APK; debug, legacy, and duplicate assets are rejected. */
    fun expectedApkName(version: String): String = "$PREFERRED_APK_PREFIX-v$version.apk"

    fun selectApkAsset(names: List<String>, version: String): String? {
        val expected = expectedApkName(version)
        return names.filter { it == expected }.singleOrNull()
    }

    fun isAllowedDownloadUrl(url: String, assetName: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" &&
            uri.host == "github.com" &&
            Regex(
                "^/nassim-arifette/anilili/releases/download/[^/]+/${Regex.escape(assetName)}$",
            ).matches(uri.path.orEmpty()) &&
            uri.query == null &&
            uri.fragment == null
    }.getOrDefault(false)

    data class ApkIdentity(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val signerSha256: Set<String>,
    )

    /** Returns a user-safe validation failure, or null when Android may install the candidate. */
    fun validateCandidate(
        current: ApkIdentity,
        candidate: ApkIdentity,
        expectedVersion: String,
    ): String? = when {
        candidate.packageName != current.packageName ->
            "Downloaded APK belongs to a different application"
        candidate.versionName != expectedVersion ->
            "Downloaded APK version does not match the GitHub release"
        candidate.versionCode <= current.versionCode ->
            "Downloaded APK does not have a newer Android version code"
        current.signerSha256.isEmpty() || candidate.signerSha256.isEmpty() ->
            "Could not verify the APK signing certificate"
        // This channel deliberately uses one permanent key and does not support key rotation.
        candidate.signerSha256 != current.signerSha256 ->
            "Downloaded APK was signed with a different certificate"
        else -> null
    }

    private fun numericVersionParts(version: String): List<Int> {
        val normalized = parseVersion(version) ?: return emptyList()
        return normalized.split('.').map { it.toIntOrNull() ?: return emptyList() }
    }
}
