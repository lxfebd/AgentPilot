package com.agentpilot.app.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The read-only composer's whole card is clickable, so a stray tap must not raise the
 * takeover confirmation. Two taps in a row are required, and the first tap must say why
 * nothing happened - it used to be swallowed in silence, which read as a broken composer.
 */
class ReadOnlyComposerTapTest {
    @Test
    fun firstTapExplainsItselfInsteadOfDoingNothing() {
        val (action, count) = readOnlyComposerTap(
            previousTapCount = 0,
            dialogOpen = false,
            takeoverInFlight = false,
            connectorOnline = true,
        )

        assertEquals(ReadOnlyComposerTap.ShowHint, action)
        assertEquals(1, count)
    }

    @Test
    fun secondTapAsksForTakeover() {
        val (action, count) = readOnlyComposerTap(
            previousTapCount = 1,
            dialogOpen = false,
            takeoverInFlight = false,
            connectorOnline = true,
        )

        assertEquals(ReadOnlyComposerTap.ConfirmTakeover, action)
        assertEquals(0, count)
    }

    @Test
    fun secondTapOnAnOfflineDeviceReportsOfflineInstead() {
        val (action, _) = readOnlyComposerTap(
            previousTapCount = 1,
            dialogOpen = false,
            takeoverInFlight = false,
            connectorOnline = false,
        )

        assertEquals(ReadOnlyComposerTap.ReportDeviceOffline, action)
    }

    @Test
    fun aStrayTapWhileTheDialogIsUpDoesNotCount() {
        val (action, count) = readOnlyComposerTap(
            previousTapCount = 1,
            dialogOpen = true,
            takeoverInFlight = false,
            connectorOnline = true,
        )

        assertEquals(ReadOnlyComposerTap.Ignore, action)
        assertEquals(1, count)
    }

    @Test
    fun aStrayTapWhileATakeoverIsRunningDoesNotCount() {
        val (action, count) = readOnlyComposerTap(
            previousTapCount = 0,
            dialogOpen = false,
            takeoverInFlight = true,
            connectorOnline = true,
        )

        assertEquals(ReadOnlyComposerTap.Ignore, action)
        assertEquals(0, count)
    }

    @Test
    fun theCountResetsAfterAConfirmedTap() {
        // A third rapid tap after the confirmation must start a fresh sequence rather than
        // reopening the dialog on its own.
        val (action, count) = readOnlyComposerTap(
            previousTapCount = 0,
            dialogOpen = false,
            takeoverInFlight = false,
            connectorOnline = true,
        )

        assertEquals(ReadOnlyComposerTap.ShowHint, action)
        assertEquals(1, count)
    }
}
