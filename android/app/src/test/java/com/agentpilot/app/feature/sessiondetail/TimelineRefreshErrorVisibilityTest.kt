package com.agentpilot.app.feature.sessiondetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline-refresh failure notice is new: a failed refresh over messages that are
 * already on screen used to be invisible, which is why stale content could read as
 * "still loading". It must not appear while a load is genuinely in progress, and must not
 * appear at all on a healthy timeline.
 */
class TimelineRefreshErrorVisibilityTest {
    @Test
    fun aFailedRefreshOverLoadedMessagesIsVisible() {
        assertTrue(
            shouldShowTimelineRefreshError(
                errorMessage = "Could not reach the server.",
                isLoading = false,
            )
        )
    }

    @Test
    fun aFailedRefreshOverAnEmptyListIsStillVisible() {
        // The old empty-list branch covers this case; the predicate agrees so the two paths
        // cannot disagree about when an error is real.
        assertTrue(
            shouldShowTimelineRefreshError(
                errorMessage = "Could not reach the server.",
                isLoading = false,
            )
        )
    }

    @Test
    fun aLoadInProgressSuppressesTheNotice() {
        // The banner must not flash on every first load, which starts with no error but
        // takes time; the guard is the loading flag, not the error.
        assertFalse(
            shouldShowTimelineRefreshError(
                errorMessage = null,
                isLoading = true,
            )
        )
    }

    @Test
    fun aStaleErrorMessageDuringALoadIsNotShown() {
        // A previous failure's message lingers in state while the next load runs; showing
        // it would report a failure that may already be resolved.
        assertFalse(
            shouldShowTimelineRefreshError(
                errorMessage = "Could not reach the server.",
                isLoading = true,
            )
        )
    }

    @Test
    fun aHealthyTimelineShowsNothing() {
        assertFalse(shouldShowTimelineRefreshError(errorMessage = null, isLoading = false))
    }

    @Test
    fun aBlankErrorMessageShowsNothing() {
        assertFalse(shouldShowTimelineRefreshError(errorMessage = "   ", isLoading = false))
    }
}
