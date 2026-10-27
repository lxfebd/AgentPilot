package com.agentpilot.app.feature.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignatureVerifierTest {

    @Test
    fun normalizeFingerprintStripsSeparatorsAndUppercasesHex() {
        assertEquals(
            "AB12CD34",
            ApkSignatureVerifier.normalizeFingerprint(" ab:12 cd-34 "),
        )
    }

    @Test
    fun normalizeFingerprintRejectsMalformedDigests() {
        assertNull(ApkSignatureVerifier.normalizeFingerprint(""))
        assertNull(ApkSignatureVerifier.normalizeFingerprint("   "))
        // odd number of hex digits once separators are stripped is never a digest
        assertNull(ApkSignatureVerifier.normalizeFingerprint("A:B:C"))
        // non-hex characters
        assertNull(ApkSignatureVerifier.normalizeFingerprint("ZZZZ"))
        assertNull(ApkSignatureVerifier.normalizeFingerprint("it is a test string!"))
    }

    @Test
    fun sha256HexMatchesPublishedVectors() {
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
            ApkSignatureVerifier.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            ApkSignatureVerifier.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun isPlausibleApkFileGatesExtensionEmptinessAndExistence() {
        val dir = Files.createTempDirectory("apk-signature-verifier").toFile()
        try {
            val apk = File(dir, "agent-pilot-2.3.5.apk").apply { writeBytes(ByteArray(8)) }
            assertTrue(ApkSignatureVerifier.isPlausibleApkFile(apk))

            val upperCaseExtension = File(dir, "agent-pilot-2.3.5.APK").apply { writeBytes(ByteArray(8)) }
            assertTrue(ApkSignatureVerifier.isPlausibleApkFile(upperCaseExtension))

            val notApk = File(dir, "notes.txt").apply { writeBytes(ByteArray(8)) }
            assertFalse(ApkSignatureVerifier.isPlausibleApkFile(notApk))

            val empty = File(dir, "empty.apk").apply { createNewFile() }
            assertFalse(ApkSignatureVerifier.isPlausibleApkFile(empty))

            assertFalse(ApkSignatureVerifier.isPlausibleApkFile(File(dir, "missing.apk")))
            assertFalse(ApkSignatureVerifier.isPlausibleApkFile(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
