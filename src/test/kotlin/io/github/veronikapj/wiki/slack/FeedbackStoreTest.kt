package io.github.veronikapj.wiki.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedbackStoreTest {

    private fun store() = FeedbackStore(dbPath = ":memory:")

    @Test
    fun `save and get entry by messageTs`() {
        val store = store()
        store.save("ts-001", FeedbackEntry(
            query = "회원가입 화면",
            answer = "SignUpActivity에서 구현",
            usedTools = listOf("confluence"),
            ts = "ts-001",
        ))
        val entry = store.get("ts-001")
        assertNotNull(entry)
        assertEquals("회원가입 화면", entry.query)
    }

    @Test
    fun `get returns null for unknown ts`() {
        val store = store()
        assertNull(store.get("unknown"))
    }

    @Test
    fun `saveReaction updates existing entry`() {
        val store = store()
        store.save("ts-002", FeedbackEntry(
            query = "push 알림", answer = "BrazeWrapper", usedTools = listOf("code_search"), ts = "ts-002",
        ))
        store.saveReaction("ts-002", "thumbsdown")
        val entry = store.get("ts-002")
        assertEquals("thumbsdown", entry?.reaction)
    }

    @Test
    fun `saveRequery updates requery fields and increments stage`() {
        val store = store()
        store.save("ts-003", FeedbackEntry(
            query = "로그아웃", answer = "AuthRepository", usedTools = listOf("confluence"), ts = "ts-003",
        ))
        store.saveRequery("ts-003", requeryBm25 = "logout signOut", requeryVec = "로그인 해제 동작", requeryAnswer = "새 답변", stage = 1)
        val entry = store.get("ts-003")
        assertEquals(1, entry?.stage)
        assertEquals("logout signOut", entry?.requeryBm25)
    }

    @Test
    fun `save 재호출 시 reaction이 유실되지 않는다`() {
        val store = store()
        store.save("ts-004", FeedbackEntry(query = "q", answer = "a", usedTools = emptyList(), ts = "ts-004"))
        store.saveReaction("ts-004", "thumbsup")
        store.save("ts-004", FeedbackEntry(query = "q2", answer = "a2", usedTools = emptyList(), ts = "ts-004"))
        assertEquals("thumbsup", store.get("ts-004")?.reaction)
    }
}
