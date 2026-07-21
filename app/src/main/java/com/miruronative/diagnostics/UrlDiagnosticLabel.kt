package com.miruronative.diagnostics

import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Returns a stable URL label that is safe to include in user-shareable diagnostics.
 *
 * Paths, query parameters, fragments, and user info may contain signed playback credentials, so
 * only the normalized host and a one-way fingerprint of the complete value are exposed.
 */
internal fun privacySafeUrlDiagnosticLabel(url: String?): String {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return "host=none fingerprint=none"

    val host = runCatching { URI(value).host }
        .getOrNull()
        ?.trimEnd('.')
        ?.takeIf(String::isNotBlank)
        ?.let { rawHost ->
            runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES) }
                .getOrNull()
        }
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.length <= MAX_DIAGNOSTIC_HOST_LENGTH }
        ?: "unknown"
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

    return "host=$host fingerprint=sha256:$fingerprint"
}

private const val MAX_DIAGNOSTIC_HOST_LENGTH = 253
