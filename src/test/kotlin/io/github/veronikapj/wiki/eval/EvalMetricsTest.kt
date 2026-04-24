package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.agent.SearchResult
import io.github.veronikapj.wiki.agent.SearchStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalMetricsTest {

    private fun result(title: String, stage: SearchStage = SearchStage.TITLE_MATCH) =
        SearchResult(
            pageId = "p-${title.hashCode()}",
            title = title,
            url = "https://wiki.example.com/${title.hashCode()}",
            snippet = "snippet for $title",
            stage = stage,
        )

    // --- hitAtK ---

    @Test
    fun `recall at K calculates correctly`() {
        val results = listOf(
            result("Getting Started Guide"),
            result("API Reference"),
            result("Deployment Process"),
            result("Monitoring Dashboard"),
            result("Incident Response"),
        )
        assertTrue(EvalMetrics.hitAtK(results, listOf("API Reference"), k = 5))
        assertTrue(EvalMetrics.hitAtK(results, listOf("API Reference"), k = 2))
        assertTrue(EvalMetrics.hitAtK(results, listOf("Getting Started Guide"), k = 1))
    }

    @Test
    fun `recall miss when expected not in results`() {
        val results = listOf(
            result("Getting Started Guide"),
            result("API Reference"),
        )
        assertFalse(EvalMetrics.hitAtK(results, listOf("Release Notes"), k = 5))
    }

    // --- reciprocalRank ---

    @Test
    fun `MRR calculation`() {
        val results = listOf(
            result("Page A"),
            result("Page B"),
            result("Target Page"),
            result("Page D"),
        )
        // Target is at position 3 (1-indexed) → RR = 1/3
        assertEquals(1.0 / 3.0, EvalMetrics.reciprocalRank(results, listOf("Target Page")), 0.001)

        // First position → RR = 1.0
        assertEquals(1.0, EvalMetrics.reciprocalRank(results, listOf("Page A")), 0.001)
    }

    @Test
    fun `MRR zero when not found`() {
        val results = listOf(result("Page A"), result("Page B"))
        assertEquals(0.0, EvalMetrics.reciprocalRank(results, listOf("Missing Page")))
    }

    // --- isHonestZero ---

    @Test
    fun `honest zero — ZERO_EXPECTED with empty results is correct`() {
        assertTrue(EvalMetrics.isHonestZero(emptyList(), Category.ZERO_EXPECTED))
    }

    @Test
    fun `honest zero — ZERO_EXPECTED with results is incorrect`() {
        val results = listOf(result("Some Page"))
        assertFalse(EvalMetrics.isHonestZero(results, Category.ZERO_EXPECTED))
    }

    @Test
    fun `honest zero — non-ZERO_EXPECTED category always false`() {
        assertFalse(EvalMetrics.isHonestZero(emptyList(), Category.EXACT_MATCH))
        assertFalse(EvalMetrics.isHonestZero(emptyList(), Category.TITLE_BASED))
    }

    // --- normalization ---

    @Test
    fun `title matching is case and whitespace tolerant`() {
        val results = listOf(
            result("  Getting   Started   Guide  "),
        )
        // Lowercase + whitespace-collapsed match
        assertTrue(
            EvalMetrics.hitAtK(results, listOf("getting started guide"), k = 1),
        )
    }

    @Test
    fun `title matching supports bidirectional contains`() {
        // Result title contains expected (partial match)
        val results = listOf(result("Complete Deployment Process Guide"))
        assertTrue(
            EvalMetrics.hitAtK(results, listOf("Deployment Process"), k = 1),
        )

        // Expected contains result title
        val results2 = listOf(result("Release"))
        assertTrue(
            EvalMetrics.hitAtK(results2, listOf("Release Notes and Changelog"), k = 1),
        )
    }

    @Test
    fun `reciprocalRank uses earliest matching position`() {
        val results = listOf(
            result("Page A"),
            result("Target Docs"),          // position 2 — partial match with "Target"
            result("Page C"),
            result("Target Docs Extended"),  // position 4 — also matches
        )
        // Should return 1/2 (earliest match at position 2)
        assertEquals(0.5, EvalMetrics.reciprocalRank(results, listOf("Target Docs")), 0.001)
    }
}
