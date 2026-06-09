package io.github.veronikapj.wiki.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

class ConversationStoreTest {

    private fun createTempStore(): ConversationStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "wiki-test-sessions-${System.nanoTime()}")
        return ConversationStore(dir.absolutePath)
    }

    @Test
    fun `append and load returns turns`() {
        val store = createTempStore()
        store.append("session1", "질문1", "답변1")
        store.append("session1", "질문2", "답변2")
        val turns = store.load("session1")
        assertEquals(2, turns.size)
        assertEquals("질문1", turns[0].question)
        assertEquals("답변2", turns[1].answer)
    }

    @Test
    fun `load returns empty for nonexistent session`() {
        val store = createTempStore()
        assertEquals(emptyList(), store.load("nonexistent"))
    }

    @Test
    fun `load returns only last maxTurns`() {
        val store = createTempStore()
        repeat(8) { i -> store.append("session1", "질문$i", "답변$i") }
        val turns = store.load("session1", maxTurns = 5)
        assertEquals(5, turns.size)
        assertEquals("질문3", turns[0].question)
        assertEquals("질문7", turns[4].question)
    }

    @Test
    fun `different sessions are isolated`() {
        val store = createTempStore()
        store.append("session1", "Q1", "A1")
        store.append("session2", "Q2", "A2")
        assertEquals(1, store.load("session1").size)
        assertEquals("Q2", store.load("session2")[0].question)
    }

    @Test
    fun `loadAll returns all turns without limit`() {
        val store = createTempStore()
        repeat(12) { i -> store.append("session1", "질문$i", "답변$i") }
        val all = store.loadAll("session1")
        assertEquals(12, all.size)
    }

    @Test
    fun `loadSummary returns null when no summary`() {
        val store = createTempStore()
        assertEquals(null, store.loadSummary("session1"))
    }

    @Test
    fun `saveSummary and loadSummary round-trip`() {
        val store = createTempStore()
        store.saveSummary("session1", "이전 대화 요약 내용")
        assertEquals("이전 대화 요약 내용", store.loadSummary("session1"))
    }

    @Test
    fun `trimOldTurns keeps only recent turns`() {
        val store = createTempStore()
        repeat(10) { i -> store.append("session1", "질문$i", "답변$i") }
        store.trimOldTurns("session1", keepRecent = 4)
        val remaining = store.loadAll("session1")
        assertEquals(4, remaining.size)
        assertEquals("질문6", remaining[0].question)
    }

    @Test
    fun `compress summarizes old turns and keeps recent`() {
        val store = createTempStore()
        repeat(12) { i -> store.append("session1", "질문$i", "답변$i") }

        val summarizer: suspend (String) -> String = { text -> "요약: ${text.lines().size}줄" }

        kotlinx.coroutines.runBlocking {
            store.compress("session1", summarizer)
        }

        val remaining = store.loadAll("session1")
        assertEquals(4, remaining.size)
        assertEquals("질문8", remaining[0].question)

        val summary = store.loadSummary("session1")
        assertNotNull(summary)
        assertTrue(summary.contains("요약"))
    }

    @Test
    fun `compress does nothing below threshold`() {
        val store = createTempStore()
        repeat(5) { i -> store.append("session1", "질문$i", "답변$i") }

        val summarizer: suspend (String) -> String = { error("should not be called") }
        kotlinx.coroutines.runBlocking {
            store.compress("session1", summarizer)
        }

        assertEquals(5, store.loadAll("session1").size)
        assertEquals(null, store.loadSummary("session1"))
    }

    @Test
    fun `compress includes previous summary in new summary`() {
        val store = createTempStore()
        store.saveSummary("session1", "기존 요약 내용")
        repeat(12) { i -> store.append("session1", "질문$i", "답변$i") }

        val summarizer: suspend (String) -> String = { text -> text }
        kotlinx.coroutines.runBlocking {
            store.compress("session1", summarizer)
        }

        val summary = store.loadSummary("session1")
        assertNotNull(summary)
        assertTrue(summary.contains("기존 요약 내용"))
    }
}
