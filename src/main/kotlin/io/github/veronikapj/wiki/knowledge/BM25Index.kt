package io.github.veronikapj.wiki.knowledge

import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager

/**
 * SQLite FTS5 기반 BM25 키워드 검색 인덱스.
 *
 * ChromaDB(벡터 검색)를 보완합니다:
 * - 벡터: "배너 클릭 이벤트 처리" 같은 의미적 질문에 강함
 * - BM25: "KMA-7275", "panelCode" 같은 정확한 키워드 매칭에 강함
 *
 * 두 결과를 RRF로 합산하면 두 가지 모두 잘 처리할 수 있습니다.
 */
class BM25Index(dbPath: String = ".wiki/bm25.db") {

    private val conn = run {
        File(dbPath).parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").also { c ->
            c.createStatement().use { stmt ->
                // FTS5 가상 테이블: id/file_path는 인덱스 제외(UNINDEXED), content만 전문 검색
                stmt.execute(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS code_chunks USING fts5(
                        id UNINDEXED,
                        file_path UNINDEXED,
                        content,
                        tokenize = 'unicode61'
                    )
                    """.trimIndent(),
                )
                // 스키마 마이그레이션: 구버전(file_path 없음)이면 DROP → 재생성
                runCatching {
                    c.prepareStatement("SELECT file_path FROM code_chunks LIMIT 1").use { it.executeQuery() }
                }.onFailure {
                    log.info("BM25 schema migration: file_path 컬럼 추가 (다음 인덱싱 시 재구축)")
                    stmt.execute("DROP TABLE IF EXISTS code_chunks")
                    stmt.execute(
                        """
                        CREATE VIRTUAL TABLE code_chunks USING fts5(
                            id UNINDEXED,
                            file_path UNINDEXED,
                            content,
                            tokenize = 'unicode61'
                        )
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    /**
     * 문서를 추가하거나 업데이트합니다.
     * FTS5는 UPDATE를 지원하지 않으므로 DELETE → INSERT 방식 사용.
     */
    fun upsert(id: String, content: String, filePath: String = "") {
        conn.prepareStatement("DELETE FROM code_chunks WHERE id = ?").use { it.setString(1, id); it.execute() }
        conn.prepareStatement("INSERT INTO code_chunks(id, file_path, content) VALUES (?, ?, ?)").use {
            it.setString(1, id)
            it.setString(2, filePath)
            it.setString(3, content)
            it.execute()
        }
    }

    /** 패턴에 매칭되는 distinct file_path 목록 반환. pattern이 비어있으면 전체. */
    fun listFilesByPattern(pattern: String): List<String> {
        return runCatching {
            val sql = if (pattern.isBlank()) {
                "SELECT DISTINCT file_path FROM code_chunks ORDER BY file_path"
            } else {
                "SELECT DISTINCT file_path FROM code_chunks WHERE file_path LIKE ? ORDER BY file_path"
            }
            conn.prepareStatement(sql).use { stmt ->
                if (pattern.isNotBlank()) stmt.setString(1, "%$pattern%")
                val rs = stmt.executeQuery()
                buildList { while (rs.next()) add(rs.getString("file_path")) }
            }
        }.onFailure { log.warn("listFilesByPattern failed for '{}': {}", pattern, it.message) }
            .getOrDefault(emptyList())
    }

    /**
     * BM25 점수 기준으로 상위 [limit]개 id를 반환합니다.
     * FTS5의 bm25()는 음수 값 반환 (더 작을수록 관련성 높음) → ORDER BY ASC.
     *
     * FTS5 MATCH 문법: 공백은 OR, "phrase" 는 구문 검색, term* 는 prefix.
     * 한국어 쿼리는 공백 분리 토큰으로 검색.
     */
    fun search(query: String, limit: Int = 10): List<String> {
        val safeQuery = sanitizeFtsQuery(query)
        if (safeQuery.isBlank()) return emptyList()
        return runCatching {
            conn.prepareStatement(
                "SELECT id FROM code_chunks WHERE code_chunks MATCH ? ORDER BY bm25(code_chunks) LIMIT ?",
            ).use { stmt ->
                stmt.setString(1, safeQuery)
                stmt.setInt(2, limit)
                val rs = stmt.executeQuery()
                buildList {
                    while (rs.next()) add(rs.getString("id"))
                }
            }
        }.onFailure { log.warn("BM25 search failed for '{}': {}", query, it.message) }
            .getOrDefault(emptyList())
    }

    fun delete(id: String) {
        conn.prepareStatement("DELETE FROM code_chunks WHERE id = ?").use { it.setString(1, id); it.execute() }
    }

    fun close() = runCatching { conn.close() }

    /**
     * FTS5 MATCH에 특수문자가 들어가면 파싱 오류 발생.
     * 영숫자/한글/공백/하이픈만 남기고, 나머지 제거.
     */
    private fun sanitizeFtsQuery(query: String): String =
        query.replace(Regex("[^\\w\\s가-힣\\-]"), " ").trim()

    companion object {
        private val log = LoggerFactory.getLogger(BM25Index::class.java)

        /**
         * RRF (Reciprocal Rank Fusion) 점수.
         * k=60 은 표준값 — 낮은 순위 결과의 영향을 줄이는 완충 상수.
         * score = 1 / (k + rank + 1), rank 은 0-indexed.
         */
        fun rrfScore(rank: Int, k: Int = 60): Double = 1.0 / (k + rank + 1)

        /**
         * 벡터 검색 id 목록 + BM25 id 목록을 RRF로 합산해 정렬된 id 목록 반환.
         *
         * 두 검색 모두에 나타나는 결과가 상위권에 옴.
         * 어느 한쪽에만 있어도 포함 (Union).
         */
        fun mergeRRF(
            vectorIds: List<String>,
            bm25Ids: List<String>,
        ): List<String> {
            val scores = mutableMapOf<String, Double>()
            vectorIds.forEachIndexed { rank, id -> scores[id] = (scores[id] ?: 0.0) + rrfScore(rank) }
            bm25Ids.forEachIndexed { rank, id -> scores[id] = (scores[id] ?: 0.0) + rrfScore(rank) }
            return scores.entries.sortedByDescending { it.value }.map { it.key }
        }
    }
}
