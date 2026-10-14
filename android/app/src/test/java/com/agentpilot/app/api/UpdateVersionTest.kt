package com.agentpilot.app.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the version comparison that drives the update prompt.
 *
 * This function decides whether the phone prompts the user to update, so a
 * regression here either silently blocks every update (server looks older) or
 * nags forever (server always looks newer).  Both failure modes are invisible
 * in code review, which is why it is covered directly.
 */
class UpdateVersionTest {

    private fun compare(left: String, right: String): Int = compareUpdateVersions(left, right)!!

    @Test
    fun ordersNumericSegmentsNumerically() {
        assertEquals(-1, compare("2.1.9", "2.1.10"))
        assertEquals(1, compare("2.1.10", "2.1.9"))
        assertEquals(0, compare("2.1.10", "2.1.10"))
    }

    @Test
    fun treatsMissingSegmentsAsZero() {
        assertEquals(-1, compare("2.1", "2.1.1"))
        assertEquals(1, compare("2.1.1", "2.1"))
        assertEquals(0, compare("2.1", "2.1.0"))
    }

    @Test
    fun handlesFourSegmentsFromServer() {
        // The Server reports a four-segment version.
        assertEquals(-1, compare("2.1.3", "2.1.3.1"))
        assertEquals(1, compare("2.1.3.1", "2.1.3"))
        assertEquals(0, compare("2.1.3", "2.1.3.0"))
    }

    @Test
    fun handlesLongNumericSegments() {
        // Segments longer than Long must not overflow.
        assertEquals(-1, compare("1.99999999999999999999", "1.100000000000000000000"))
        assertEquals(1, compare("1.100000000000000000000", "1.99999999999999999999"))
    }

    @Test
    fun ignoresLeadingV() {
        assertEquals(0, compare("v2.1.3", "2.1.3"))
        assertEquals(-1, compare("v2.1.2", "2.1.3"))
    }

    @Test
    fun ignoresBuildMetadata() {
        assertEquals(0, compare("2.1.3+build.1", "2.1.3"))
        assertEquals(0, compare("2.1.3", "2.1.3+build.999"))
    }

    @Test
    fun releaseOutranksPreRelease() {
        assertEquals(1, compare("2.1.3", "2.1.3-rc.1"))
        assertEquals(-1, compare("2.1.3-rc.1", "2.1.3"))
        assertEquals(1, compare("2.1.3", "2.1.3-beta"))
    }

    @Test
    fun ordersPreReleaseIdentifiers() {
        assertEquals(-1, compare("2.1.3-alpha", "2.1.3-beta"))
        assertEquals(1, compare("2.1.3-beta", "2.1.3-alpha"))
        assertEquals(-1, compare("2.1.3-alpha.1", "2.1.3-alpha.2"))
        // Numeric identifiers compare numerically, not lexically.
        assertEquals(-1, compare("2.1.3-rc.9", "2.1.3-rc.10"))
        assertEquals(1, compare("2.1.3-rc.10", "2.1.3-rc.9"))
    }

    @Test
    fun numericPreReleaseIdentifierOutranksAlphabetic() {
        assertEquals(-1, compare("2.1.3-rc.1", "2.1.3-rc.beta"))
        assertEquals(1, compare("2.1.3-rc.beta", "2.1.3-rc.1"))
    }

    @Test
    fun shorterPreReleaseChainIsOlderWhenPrefixMatches() {
        assertEquals(-1, compare("2.1.3-alpha", "2.1.3-alpha.1"))
        assertEquals(1, compare("2.1.3-alpha.1", "2.1.3-alpha"))
    }

    @Test
    fun supportsShortPreReleaseSuffixes() {
        assertEquals(-1, compare("2.1.3a1", "2.1.3a2"))
        assertEquals(-1, compare("2.1.3dev1", "2.1.3dev2"))
        // Only the sign is contractual; alphabetic identifiers compare lexically.
        assertTrue(compare("2.1.3b2", "2.1.3rc1") < 0)
        assertTrue(compare("2.1.3rc1", "2.1.3b2") > 0)
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(0, compare("  2.1.3  ", "2.1.3"))
    }

    @Test
    fun returnsNullForUnparsableInput() {
        assertNull(compareUpdateVersions("", "2.1.3"))
        assertNull(compareUpdateVersions("2.1.3", ""))
        assertNull(compareUpdateVersions("not-a-version", "2.1.3"))
        assertNull(compareUpdateVersions("2.1.3", "latest"))
        assertNull(compareUpdateVersions("2..3", "2.1.3"))
        assertNull(compareUpdateVersions("2.1.3", "2.1.3-"))
    }

    @Test
    fun returnsNullForOverlongInput() {
        val tooLong = "1".repeat(129)
        assertNull(compareUpdateVersions(tooLong, "2.1.3"))
    }

    @Test
    fun comparisonIsAntisymmetric() {
        val pairs = listOf(
            "2.1.3" to "2.1.4",
            "2.1.3" to "2.1.3-rc.1",
            "2.1.3-rc.1" to "2.1.3-rc.2",
            "2.1" to "2.1.0.1",
            "v2.1.3+b1" to "2.1.4",
        )
        for ((left, right) in pairs) {
            val forward = compareUpdateVersions(left, right)!!
            val backward = compareUpdateVersions(right, left)!!
            assertEquals("sign($left,$right)", -forward, backward)
        }
    }

    @Test
    fun comparisonIsTransitive() {
        assertEquals(-1, compare("2.1.3-alpha.1", "2.1.3-alpha.2"))
        assertEquals(-1, compare("2.1.3-alpha.2", "2.1.3-beta"))
        assertEquals(-1, compare("2.1.3-alpha.1", "2.1.3-beta"))
        assertEquals(1, compare("2.1.4", "2.1.3-beta"))
        assertEquals(1, compare("2.1.3-beta", "2.1.3-alpha"))
    }
}
