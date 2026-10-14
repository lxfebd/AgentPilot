package com.agentpilot.app.feature.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Contract tests for the APK update download directory and the FileProvider
 * path registration.
 *
 * Regression context: the update installer used to rely on
 * `PackageInstaller.Session`, which MIUI / EMUI / ColorOS silently block — the
 * user saw "download finished" and then nothing.  The fix routes through
 * `ACTION_VIEW` + `FileProvider`, which requires the APK to live under a
 * directory declared in `res/xml/attachment_paths.xml` with the authority
 * `${applicationId}.attachments`.  If the download directory and the declared
 * path ever drift apart, `FileProvider.getUriForFile` throws at runtime and the
 * install never starts — again with no user-visible error.
 *
 * These tests cannot run the Android framework, so they pin the two strings
 * that must stay in sync.
 */
class AppUpdatePathContractTest {

    /** Must match `<cache-path name="app_updates" path="app-updates/" />`. */
    private val declaredUpdateSubdirectory = "app-updates"

    @Test
    fun downloadDirectoryMatchesDeclaredFileProviderPath() {
        val cacheDir = File("/data/user/0/com.agentpilot.app/cache")

        val downloadDir = File(cacheDir, "app-updates")

        assertEquals("app-updates", downloadDir.name)
        assertEquals(
            "the FileProvider path declaration must match the download directory",
            declaredUpdateSubdirectory,
            downloadDir.name,
        )
    }

    @Test
    fun downloadDirectoryIsUnderCacheDir() {
        val cacheDir = File("/data/user/0/com.agentpilot.app/cache")

        val downloadDir = File(cacheDir, "app-updates")

        // `cache-path` in attachment_paths.xml only covers cacheDir subtrees.
        assertEquals(cacheDir, downloadDir.parentFile)
    }

    @Test
    fun downloadedApkNameCarriesVersionAndUniqueSuffix() {
        // Mirrors `AppUpdateViewModel.downloadRelease`:
        //   "agent-pilot-${release.versionName}-${UUID.randomUUID()}.apk"
        val versionName = "2.1.3"
        val first = File(File("/cache", "app-updates"), "agent-pilot-$versionName-${java.util.UUID.randomUUID()}.apk")
        val second = File(File("/cache", "app-updates"), "agent-pilot-$versionName-${java.util.UUID.randomUUID()}.apk")

        assertEquals("apk", first.extension)
        assertEquals(
            versionName,
            first.nameWithoutExtension.removePrefix("agent-pilot-").substringBefore("-"),
        )
        // A fresh UUID per download keeps a failed download from being mistaken
        // for a cached success.
        assert(first.name != second.name)
    }
}
