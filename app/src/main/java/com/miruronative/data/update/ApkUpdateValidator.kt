package com.miruronative.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Reads package metadata from the installed app and downloaded APK before opening the installer. */
internal object ApkUpdateValidator {
    fun validate(context: Context, apk: File, expectedVersion: String) {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val current = packageManager.getPackageInfo(context.packageName, flags)
        @Suppress("DEPRECATION")
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Downloaded file is not a valid Android APK")

        UpdateReleasePolicy.validateCandidate(
            current = current.toIdentity(),
            candidate = candidate.toIdentity(),
            expectedVersion = expectedVersion,
        )?.let(::error)
    }

    private fun PackageInfo.toIdentity(): UpdateReleasePolicy.ApkIdentity =
        UpdateReleasePolicy.ApkIdentity(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION")
                versionCode.toLong()
            },
            signerSha256 = signerDigests(),
        )

    private fun PackageInfo.signerDigests(): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val details = signingInfo ?: return emptySet()
            if (details.hasMultipleSigners()) details.apkContentsSigners else details.signingCertificateHistory
        } else {
            this.signatures
        }
        return signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
