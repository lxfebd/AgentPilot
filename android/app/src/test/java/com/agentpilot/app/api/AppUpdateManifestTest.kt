package com.agentpilot.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Unit tests for the APK update manifest and the decision it drives.
 *
 * Regression context: the update check asked the Server `/health` which version
 * it was, and compared that against the installed Android version. Server and
 * Android PATCH versions are released independently (docs/versioning.md), so
 * with Server 2.1.1 and Android 2.1.3 the comparison was `<= 0` forever and the
 * Android-only releases 2.1.2 / 2.1.3 could never reach a phone. Bumping the
 * Server version instead would have falsely offered Desktop updates, because
 * Desktop compares that same value against its own build-info.
 *
 * `<origin>/apk/version.json` now describes the APK that is actually served, and
 * its `versionCode` decides. The transport is injected, so none of this needs a
 * network or an Android context.
 */
class AppUpdateManifestTest {

    private class FakeTransport(
        private val manifestBody: String? = null,
        private val manifestUnreachable: Boolean = false,
        private val healthBody: JSONObject? = null,
    ) : UpdateTransport {
        var requestedManifestUrl: String? = null
            private set
        var healthCalls: Int = 0
            private set

        override fun fetchManifest(manifestUrl: String): String? {
            requestedManifestUrl = manifestUrl
            if (manifestUnreachable) {
                throw ApiException("Could not reach the server. Check the URL and network.")
            }
            return manifestBody
        }

        override fun fetchHealth(serverUrl: String): JSONObject {
            healthCalls++
            return healthBody
                ?: throw ApiException("Endpoint was not found on this server.", statusCode = 404)
        }
    }

    private companion object {
        const val SERVER = "https://example.com"

        /** The installed app at the time 2.1.2 / 2.1.3 were unreachable. */
        const val INSTALLED_NAME = "2.1.3"
        const val INSTALLED_CODE = 19

        fun health(version: String): JSONObject =
            JSONObject().put("status", "ok").put("version", version)
    }

    // --- manifest parsing -------------------------------------------------

    @Test
    fun parsesManifestWithVersionNameAndCode() {
        val manifest = parseApkReleaseManifest(
            """{"versionName":"2.2.0","versionCode":20,"publishedAt":"2026-10-14T09:00:00Z"}""",
        )

        assertNotNull(manifest)
        assertEquals("2.2.0", manifest!!.versionName)
        assertEquals(20, manifest.versionCode)
        assertEquals("2026-10-14T09:00:00Z", manifest.publishedAt)
    }

    @Test
    fun parsesManifestWithoutVersionCode() {
        val manifest = parseApkReleaseManifest("""{"versionName":"2.2.0"}""")

        assertNotNull(manifest)
        assertEquals("2.2.0", manifest!!.versionName)
        assertNull(manifest.versionCode)
        assertNull(manifest.publishedAt)
    }

    @Test
    fun parsesManifestWithoutVersionName() {
        val manifest = parseApkReleaseManifest("""{"versionCode":20}""")

        assertNotNull(manifest)
        assertNull(manifest!!.versionName)
        assertEquals(20, manifest.versionCode)
        // The update UI and the downloaded file name still need a label.
        assertEquals("20", manifest.displayVersionName)
    }

    @Test
    fun acceptsNumericStringVersionCode() {
        val manifest = parseApkReleaseManifest("""{"versionName":"2.2.0","versionCode":"20"}""")

        assertEquals(20, manifest!!.versionCode)
    }

    @Test
    fun treatsNonPositiveVersionCodeAsAbsent() {
        assertNull(parseApkReleaseManifest("""{"versionName":"2.2.0","versionCode":0}""")?.versionCode)
        assertNull(parseApkReleaseManifest("""{"versionName":"2.2.0","versionCode":-7}""")?.versionCode)
    }

    @Test
    fun treatsNonNumericVersionCodeAsAbsent() {
        val manifest = parseApkReleaseManifest("""{"versionName":"2.2.0","versionCode":"latest"}""")

        assertNotNull(manifest)
        assertNull(manifest!!.versionCode)
        assertEquals("2.2.0", manifest.versionName)
    }

    @Test
    fun ignoresBlankVersionName() {
        val manifest = parseApkReleaseManifest("""{"versionName":"   ","versionCode":20}""")

        assertNotNull(manifest)
        assertNull(manifest!!.versionName)
        assertEquals(20, manifest.versionCode)
    }

    @Test
    fun treatsJsonNullVersionFieldsAsAbsent() {
        assertNull(
            parseApkReleaseManifest("""{"versionName":null,"versionCode":null,"publishedAt":null}"""),
        )
    }

    @Test
    fun rejectsManifestWithoutAnyUsableVersion() {
        assertNull(parseApkReleaseManifest("""{"publishedAt":"2026-10-14T09:00:00Z"}"""))
        assertNull(parseApkReleaseManifest("{}"))
    }

    @Test
    fun rejectsMalformedManifest() {
        assertNull(parseApkReleaseManifest("not json at all"))
        assertNull(parseApkReleaseManifest("<html>404 Not Found</html>"))
        assertNull(parseApkReleaseManifest("""{"versionName":"2.2.0"""))
        assertNull(parseApkReleaseManifest("[1,2,3]"))
        assertNull(parseApkReleaseManifest(""))
        assertNull(parseApkReleaseManifest("   "))
        assertNull(parseApkReleaseManifest(null))
    }

    // --- newer / older / equal --------------------------------------------

    private fun manifestOf(versionName: String? = null, versionCode: Int? = null): ApkReleaseManifest =
        ApkReleaseManifest(versionName = versionName, versionCode = versionCode, publishedAt = null)

    @Test
    fun versionCodeDecidesWhenTheManifestCarriesOne() {
        assertTrue(isServedApkNewer(manifestOf("2.2.0", 20), INSTALLED_NAME, INSTALLED_CODE))
        assertFalse(isServedApkNewer(manifestOf("2.1.3", 19), INSTALLED_NAME, INSTALLED_CODE))
        assertFalse(isServedApkNewer(manifestOf("2.2.0", 18), INSTALLED_NAME, INSTALLED_CODE))
    }

    @Test
    fun versionNameDecidesWhenVersionCodeIsAbsent() {
        assertTrue(isServedApkNewer(manifestOf("2.2.0"), INSTALLED_NAME, INSTALLED_CODE))
        assertFalse(isServedApkNewer(manifestOf("2.1.3"), INSTALLED_NAME, INSTALLED_CODE))
        assertFalse(isServedApkNewer(manifestOf("2.1.2"), INSTALLED_NAME, INSTALLED_CODE))
        // PATCH segments compare numerically, not lexically.
        assertTrue(isServedApkNewer(manifestOf("2.1.10"), "2.1.9", INSTALLED_CODE))
    }

    @Test
    fun versionCodeOutranksVersionName() {
        // A name that looks older but a higher code is still a newer APK build.
        assertTrue(isServedApkNewer(manifestOf("2.1.0", 20), INSTALLED_NAME, INSTALLED_CODE))
        // A name that looks newer but the same code is the APK already installed.
        assertFalse(isServedApkNewer(manifestOf("3.0.0", 19), INSTALLED_NAME, INSTALLED_CODE))
    }

    @Test
    fun manifestWithoutUsableVersionIsNeverNewer() {
        assertFalse(isServedApkNewer(manifestOf(), INSTALLED_NAME, INSTALLED_CODE))
        // An unparsable manifest name must not produce a bogus update either.
        assertFalse(isServedApkNewer(manifestOf("latest"), INSTALLED_NAME, INSTALLED_CODE))
    }

    // --- the check, through the injected transport ------------------------

    @Test
    fun offersUpdateFromApkManifestEvenWhenServerVersionIsOlder() {
        // The exact 2.1.1-server / 2.1.3-phone situation that blocked every update.
        val transport = FakeTransport(
            manifestBody = """{"versionName":"2.2.0","versionCode":20,"publishedAt":"2026-10-14T09:00:00Z"}""",
            healthBody = health("2.1.1"),
        )

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals("2.2.0", release!!.versionName)
        assertEquals(20, release.versionCode)
        assertEquals("$SERVER/apk/latest.apk", release.downloadUrl)
        // The manifest is authoritative: the server version is not consulted.
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun doesNotOfferUpdateWhenServedApkIsNotNewer() {
        val transport = FakeTransport(
            manifestBody = """{"versionName":"2.1.3","versionCode":19}""",
            healthBody = health("2.9.9"),
        )

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNull(release)
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun manifestAndDownloadComeFromTheSameOrigin() {
        val transport = FakeTransport(manifestBody = """{"versionName":"2.2.0","versionCode":20}""")

        val release = AppUpdatesApi(transport)
            .check("https://example.com/api/v2", INSTALLED_NAME, INSTALLED_CODE)

        assertEquals("$SERVER/apk/version.json", transport.requestedManifestUrl)
        assertEquals("$SERVER/apk/latest.apk", release!!.downloadUrl)
    }

    @Test
    fun selfHostedServerChecksItsOwnApkChannel() {
        val transport = FakeTransport(manifestBody = """{"versionName":"2.2.0","versionCode":20}""")

        val release = AppUpdatesApi(transport).check("http://127.0.0.1:5174", INSTALLED_NAME, INSTALLED_CODE)

        assertEquals("http://127.0.0.1:5174/apk/version.json", transport.requestedManifestUrl)
        assertEquals("http://127.0.0.1:5174/apk/latest.apk", release!!.downloadUrl)
    }

    @Test
    fun manifestWithoutVersionCodeIsOfferedByItsName() {
        val transport = FakeTransport(manifestBody = """{"versionName":"2.2.0"}""")

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals("2.2.0", release!!.versionName)
        assertNull(release.versionCode)
        assertEquals(0, transport.healthCalls)
    }

    @Test
    fun manifestWithOnlyVersionCodeIsOfferedByItsCode() {
        val transport = FakeTransport(manifestBody = """{"versionCode":20}""")

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals(20, release!!.versionCode)
        assertEquals("20", release.versionName)
    }

    @Test
    fun fallsBackToServerVersionWhenManifestIsMissing() {
        val transport = FakeTransport(manifestBody = null, healthBody = health("2.2.0"))

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals("2.2.0", release!!.versionName)
        assertNull(release.versionCode)
        assertEquals("$SERVER/apk/latest.apk", release.downloadUrl)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun fallsBackToServerVersionWhenManifestIsMalformed() {
        val transport = FakeTransport(
            manifestBody = "<html><body>404 Not Found</body></html>",
            healthBody = health("2.2.0"),
        )

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals("2.2.0", release!!.versionName)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun fallsBackToServerVersionWhenManifestFetchFails() {
        // An unreachable manifest must not become a crash: the update check is
        // best-effort and its failure must never break login or the UI.
        val transport = FakeTransport(manifestUnreachable = true, healthBody = health("2.2.0"))

        val release = AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)

        assertNotNull(release)
        assertEquals("2.2.0", release!!.versionName)
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun olderServerWithoutManifestStillOffersNothing() {
        // Exactly how a pre-manifest deployment behaved before this change.
        val transport = FakeTransport(manifestBody = null, healthBody = health("2.1.3"))

        assertNull(AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE))
        assertEquals(1, transport.healthCalls)
    }

    @Test
    fun serverHealthFailureStillSurfacesAsApiException() {
        // The fallback keeps its original failure type; nothing new escapes.
        val transport = FakeTransport(manifestBody = null, healthBody = null)

        assertThrows(ApiException::class.java) {
            AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)
        }
    }

    @Test
    fun healthWithoutValidVersionStillSurfacesAsApiException() {
        val transport = FakeTransport(
            manifestBody = null,
            healthBody = JSONObject().put("status", "ok").put("version", "latest"),
        )

        assertThrows(ApiException::class.java) {
            AppUpdatesApi(transport).check(SERVER, INSTALLED_NAME, INSTALLED_CODE)
        }
    }
}
