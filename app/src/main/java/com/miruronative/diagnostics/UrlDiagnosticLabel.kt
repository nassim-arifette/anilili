package com.miruronative.diagnostics

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
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

    val host = normalizedDiagnosticHost(value) ?: "unknown"
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

    return "host=$host fingerprint=sha256:$fingerprint"
}

private fun normalizedDiagnosticHost(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val rawHost = uri.host ?: uri.rawAuthority?.authorityHostOrNull() ?: return null
    val unbracketed = rawHost.removeSurrounding("[", "]").trimEnd('.')
    if (unbracketed.isBlank()) return null

    if (':' in unbracketed) {
        return unbracketed
            .takeIf { it.length <= MAX_IPV6_LITERAL_LENGTH && IPV6_LITERAL.matches(it) }
            ?.takeIf { candidate ->
                runCatching { InetAddress.getByName(candidate) }.getOrNull() is Inet6Address
            }
            ?.lowercase(Locale.ROOT)
    }

    return runCatching { IDN.toASCII(unbracketed, IDN.USE_STD3_ASCII_RULES) }
        .getOrNull()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotBlank() && it.length <= MAX_DIAGNOSTIC_HOST_LENGTH }
}

/** Extract the host from a Unicode authority, for which [URI.getHost] deliberately returns null. */
private fun String.authorityHostOrNull(): String? {
    val withoutUserInfo = substringAfterLast('@')
    if (withoutUserInfo.startsWith('[')) {
        val closingBracket = withoutUserInfo.indexOf(']')
        return withoutUserInfo
            .takeIf { closingBracket > 1 }
            ?.substring(startIndex = 1, endIndex = closingBracket)
    }

    val portSeparator = withoutUserInfo.lastIndexOf(':')
    val port = withoutUserInfo.substring(portSeparator + 1)
    return if (
        portSeparator > 0 &&
        withoutUserInfo.indexOf(':') == portSeparator &&
        port.isNotEmpty() &&
        port.all(Char::isDigit)
    ) {
        withoutUserInfo.substring(0, portSeparator)
    } else {
        withoutUserInfo
    }
}

private const val MAX_DIAGNOSTIC_HOST_LENGTH = 253
private const val MAX_IPV6_LITERAL_LENGTH = 45
private val IPV6_LITERAL = Regex("^[0-9a-fA-F:.]+$")
