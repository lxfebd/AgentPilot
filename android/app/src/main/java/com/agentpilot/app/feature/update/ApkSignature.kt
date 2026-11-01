package com.agentpilot.app.feature.update

import java.security.MessageDigest

/**
 * Pure signer-certificate comparison for the update flow.
 *
 * Kept separate from [ApkSignatureVerifier] (which touches PackageManager) so the
 * comparison itself stays JVM-testable: both sides are reduced to the SET of
 * SHA-256 digests over the raw certificate bytes, and the two sets must be equal.
 */

/** SHA-256 over [bytes], hex-encoded without separators — a comparison-safe set key. */
internal fun sha256DigestHex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * True when [apkCerts] and [installedCerts] carry the same set of signer
 * certificate digests — order-insensitive, duplicates collapse to one digest.
 *
 * Fails closed: an empty side (unsigned or unparsable package) never matches.
 */
fun signerDigestsEqual(installedCerts: List<ByteArray>, apkCerts: List<ByteArray>): Boolean {
    if (installedCerts.isEmpty() || apkCerts.isEmpty()) return false
    val installed = installedCerts.mapTo(HashSet()) { sha256DigestHex(it) }
    val apk = apkCerts.mapTo(HashSet()) { sha256DigestHex(it) }
    return installed == apk
}
