package com.agentpilot.app.feature.sessiondetail

import com.agentpilot.app.api.PlanDocument
import com.agentpilot.app.api.PlanReview
import com.agentpilot.app.api.RemotePlanningState
import com.agentpilot.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every review outcome maps to a label, and only for the exact plan version the
 * review is about. The port shipped the mapping without a test; a wrong branch
 * here shows the user the wrong state of their plan.
 */
class PlanReviewStatusTest {

    private fun document(revision: Int = 1) = PlanDocument(
        planId = "p1", revision = revision, contentHash = "sha256:$revision", title = "t", markdown = "m",
    )

    private fun state(review: PlanReview?, executionStatus: String? = null) = RemotePlanningState(
        sessionId = "s1", effectiveMode = "plan", freshness = "fresh", epoch = "e1", revision = 1,
        draft = null, proposal = null, review = review, documents = emptyList(), actions = null,
        executionStatus = executionStatus,
    )

    private fun review(status: String, planId: String = "p1", revision: Int = 1) = PlanReview(
        reviewId = "r1", planId = planId, planRevision = revision, contentHash = "sha256:$revision", status = status,
    )

    @Test
    fun anApprovedPlanNamesTheExecutionOutcome() {
        assertEquals(R.string.planning_status_approved, planReviewStatus(state(review("approved")), document()))
        assertEquals(
            R.string.planning_status_executed,
            planReviewStatus(state(review("approved"), executionStatus = "started"), document()),
        )
        assertEquals(
            R.string.planning_status_execution_pending,
            planReviewStatus(state(review("approved"), executionStatus = "rejected"), document()),
        )
    }

    @Test
    fun everyOpenReviewOutcomeHasALabel() {
        val expected = mapOf(
            "open" to R.string.planning_status_open,
            "responding" to R.string.planning_status_responding,
            "revision_requested" to R.string.planning_status_revision_requested,
            "dismissed" to R.string.planning_status_dismissed,
            "withdrawn" to R.string.planning_status_withdrawn,
            "unknown" to R.string.planning_status_unknown,
        )
        for ((status, label) in expected) {
            assertEquals("status=$status", label, planReviewStatus(state(review(status)), document()))
        }
    }

    @Test
    fun aReviewOfAnotherPlanOrVersionIsNotShownOnThisCard() {
        assertNull(planReviewStatus(state(review("open", planId = "other")), document()))
        assertNull(planReviewStatus(state(review("open", revision = 2)), document()))
    }

    @Test
    fun aSessionWithoutAReviewHasNoStatus() {
        assertNull(planReviewStatus(state(null), document()))
    }

    @Test
    fun anUnrecognisedStatusHasNoLabelRatherThanAWrongOne() {
        assertNull(planReviewStatus(state(review("something_else")), document()))
    }
}
