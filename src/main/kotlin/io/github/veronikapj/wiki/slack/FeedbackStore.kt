package io.github.veronikapj.wiki.slack

import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

data class FeedbackEntry(
    val query: String,
    val answer: String,
    val usedTools: List<String>,
    val ts: String,
    val reaction: String? = null,
    val requeryBm25: String? = null,
    val requeryVec: String? = null,
    val requeryAnswer: String? = null,
    val stage: Int = 0,
)

class FeedbackStore(dbPath: String = ".wiki/feedback.db") {

    private val cache = ConcurrentHashMap<String, FeedbackEntry>()
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { createTable(it) }

    private fun createTable(c: java.sql.Connection) {
        c.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    ts TEXT PRIMARY KEY,
                    query TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    used_tools TEXT NOT NULL,
                    reaction TEXT,
                    requery_bm25 TEXT,
                    requery_vec TEXT,
                    requery_answer TEXT,
                    stage INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    fun save(ts: String, entry: FeedbackEntry) {
        conn.prepareStatement("""
            INSERT INTO feedback(ts,query,answer,used_tools,created_at)
            VALUES(?,?,?,?,?)
            ON CONFLICT(ts) DO UPDATE SET
                query=excluded.query,
                answer=excluded.answer,
                used_tools=excluded.used_tools
        """.trimIndent()).use { ps ->
            ps.setString(1, ts)
            ps.setString(2, entry.query)
            ps.setString(3, entry.answer)
            ps.setString(4, entry.usedTools.joinToString(","))
            ps.setLong(5, System.currentTimeMillis())
            ps.executeUpdate()
        }
        // DB 성공 후 메모리 갱신 — 기존 reaction/requery 필드 보존
        cache.merge(ts, entry) { existing, new ->
            new.copy(
                reaction = existing.reaction,
                requeryBm25 = existing.requeryBm25,
                requeryVec = existing.requeryVec,
                requeryAnswer = existing.requeryAnswer,
                stage = existing.stage,
            )
        }
    }

    fun get(ts: String): FeedbackEntry? = cache[ts]

    fun saveReaction(ts: String, reaction: String) {
        conn.prepareStatement("UPDATE feedback SET reaction=? WHERE ts=?").use {
            it.setString(1, reaction)
            it.setString(2, ts)
            it.executeUpdate()
        }
        cache.computeIfPresent(ts) { _, e -> e.copy(reaction = reaction) }
    }

    fun saveRequery(ts: String, requeryBm25: String, requeryVec: String, requeryAnswer: String, stage: Int) {
        conn.prepareStatement(
            "UPDATE feedback SET requery_bm25=?,requery_vec=?,requery_answer=?,stage=? WHERE ts=?"
        ).use {
            it.setString(1, requeryBm25)
            it.setString(2, requeryVec)
            it.setString(3, requeryAnswer)
            it.setInt(4, stage)
            it.setString(5, ts)
            it.executeUpdate()
        }
        cache.computeIfPresent(ts) { _, e ->
            e.copy(requeryBm25 = requeryBm25, requeryVec = requeryVec, requeryAnswer = requeryAnswer, stage = stage)
        }
    }
}
