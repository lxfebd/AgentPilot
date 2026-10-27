package com.agentpilot.app.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Verifies that a downloaded update APK carries the same signing identity as the
 * installed app before the system installer is launched.
 *
 * Threat model: the APK download URL comes from a user-configured server. A
 * malicious or compromised server (or an interceptable cleartext LAN channel)
 * must not be able to push a repackaged APK onto a device already running
 * AgentPilot. Requiring the downloaded APK's signing identity to match the
 * running app's signer makes such an install impossible without the release
 * key, and it is a purely local check that needs no extra server contract.
 *
 * Identity is compared through SHA-256 fingerprints of the signer public keys
 * (API 28+, via SigningInfo.publicKeys) or of the raw certificate bytes (API
 * 27-). Both paths compare like-for-like on any given device, and the helper
 * functions used to fingerprint the material are pure JVM logic covered by
 * unit tests.
 */
object ApkSignatureVerifier {

    enum class Verdict {
        /** The APK is signed with the same identity as the installed app. */
        Verified,

        /** The APK is signed by a different identity - refuse the install. */
        Mismatch,

        /** The APK could not be parsed as a package, or carries no signer. */
        Unreadable,
    }

    /**
     * Normalises a certificate fingerprint for comparison: drops the usual
     * separators and uppercases the hex. Returns null when the input is not a
     * well-formed hex digest so callers fail closed instead of comparing
     * unnormalised garbage.
     */
    fun normalizeFingerprint(value: String): String? {
        val cleaned = buildString {
            for (ch in value) {
                if (ch != ' ' && ch != ':' && ch != '-') append(ch)
            }
        }.uppercase()
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (cleaned.any { it !in "0123456789ABCDEF" }) return null
        return cleaned
    }

    /** Cheap sanity gate for a downloaded file before parsing it as a package. */
    fun isPlausibleApkFile(file: File): Boolean =
        file.isFile && file.length() > 0L && file.name.endsWith(".apk", ignoreCase = true)

    /** Colon-separated uppercase hex SHA-256, matching keytool -list -v output. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { byte -> "%02X".format(byte) }

    private fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** SHA-256 fingerprints of every signer of a parsed package, or null when absent. */
    private fun signerFingerprints(info: PackageInfo?): Set<String>? {
        if (info == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            val keys = signingInfo.publicKeys ?: return null
            if (keys.isEmpty()) return null
            return keys.map { sha256Hex(it.encoded) }.toSet()
        }
        @Suppress("DEPRECATION")
        val signatures = info.signatures ?: return null
        if (signatures.isEmpty()) return null
        return signatures.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    /**
     * Compares the signer fingerprint set of [apk] with the set of the currently
     * installed app. Any parse failure, missing signature, or non-file input
     * yields [Verdict.Unreadable], so a broken download is treated as unsafe
     * rather than passed to the installer.
     */
    fun verifyAgainstInstalled(context: Context, apk: File): Verdict {
        if (!isPlausibleApkFile(apk)) return Verdict.Unreadable
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apkFingerprints = signerFingerprints(pm.getPackageArchiveInfo(apk.path, signerFlags()))
            ?: return Verdict.Unreadable
        val installedFingerprints = signerFingerprints(pm.getPackageInfo(context.packageName, signerFlags()))
            ?: return Verdict.Unreadable
        return if (apkFingerprints == installedFingerprints) Verdict.Verified else Verdict.Mismatch
    }
}
