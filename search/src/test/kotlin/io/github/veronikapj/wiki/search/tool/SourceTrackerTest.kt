package io.github.veronikapj.wiki.search.tool

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceTrackerTest {

    @Test
    fun `record increments count for same source`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("GitHub Wiki")
        assertEquals(2, tracker.countOf("Confluence"))
        assertEquals(1, tracker.countOf("GitHub Wiki"))
    }

    @Test
    fun `formatFooter returns formatted string`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("Confluence")
        tracker.record("GitHub Wiki")
        assertEquals("📋 Confluence 3건 · GitHub Wiki 1건", tracker.formatFooter())
    }

    @Test
    fun `formatFooter returns empty when no sources`() {
        val tracker = SourceTracker()
        assertEquals("", tracker.formatFooter())
    }

    @Test
    fun `reset clears all counts`() {
        val tracker = SourceTracker()
        tracker.record("Confluence")
        tracker.reset()
        assertEquals("", tracker.formatFooter())
    }
}
