package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
    private val vectorSearchAgent: VectorSearchAgent? = null,
    private val sufficientThreshold: Int = 3,
) {
    suspend fun searchStructured(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
    ): List<SearchResult> {
        val cleaned = cleanQuery(query)
        log.info("Searching: query='{}' → cleaned='{}', synonyms={}, spaces={}, dateAfter={}, dateBefore={}",
            query, cleaned, synonyms, spaces, dateAfter, dateBefore)

        // 1단계: 설정 스페이스에서 제목 검색
        val titleResults = confluenceClient.searchByTitle(cleaned, spaces, synonyms, topK, dateAfter, dateBefore)
        log.info("Title search: {} results", titleResults.size)

        // Early return: 제목 매칭 충분하면 추가 검색 안 함
        if (titleResults.size >= sufficientThreshold) {
            log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
            log.info("Title results: {}", titleResults.take(5).joinToString { "\"${it.title}\"" })
            val earlyResults = titleResults.take(topK).map { it.toSearchResult(SearchStage.TITLE_MATCH) }
            return reRankByOriginalQuestion(earlyResults, originalQuestion)
        }

        // 2단계: 부족 → 병렬로 text + 스페이스 확장 + RAG
        log.info("Insufficient title matches ({}<{}), parallel fallback", titleResults.size, sufficientThreshold)
        val (textResults, expandedResults, ragResults) = coroutineScope {
            val textDeferred = async {
                runCatching { confluenceClient.searchByText(cleaned, spaces, synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                if (spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(cleaned, emptyList(), synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
                } else emptyList()
            }
            val ragDeferred = async {
                if (vectorSearchAgent != null) {
                    withTimeoutOrNull(RAG_TIMEOUT_MS) {
                        runCatching { vectorSearchAgent.searchStructured(query, topK) }.getOrElse { emptyList() }
                    } ?: run {
                        log.warn("RAG search timed out after {}ms", RAG_TIMEOUT_MS)
                        emptyList()
                    }
                } else emptyList()
            }
            Triple(textDeferred.await(), expandedDeferred.await(), ragDeferred.await())
        }

        // 3-1단계: keyword fallback (text search 0건 시) — AND/OR는 의도에 따라 결정
        val keywordResults = if (textResults.isEmpty()) {
            val keywords = extractSignificantKeywords(cleaned)
            if (keywords.size >= 2) {
                val useOr = isConversationalQuery(keywords, cleaned)
                log.info("Text search empty, trying keyword {} fallback: {}", if (useOr) "OR" else "AND", keywords)
                runCatching {
                    confluenceClient.searchByKeywords(keywords, spaces, topK, dateAfter, dateBefore, useOr = useOr)
                }.getOrElse { emptyList() }
            } else emptyList()
        } else emptyList()
        log.info("Keyword fallback: {} results", keywordResults.size)

        // 3단계: 합산 + 중복 제거 + 랭킹
        return reRankByOriginalQuestion(
            combineAndRank(titleResults, textResults, expandedResults, ragResults, keywordResults, topK),
            originalQuestion,
        )
    }

    private fun reRankByOriginalQuestion(results: List<SearchResult>, originalQuestion: String): List<SearchResult> {
        if (originalQuestion.isBlank()) return results
        val keywords = extractSignificantKeywords(originalQuestion)
        if (keywords.isEmpty()) return results
        val keywordsLower = keywords.map { it.lowercase() }
        val reRanked = results.sortedByDescending { page ->
            val titleLower = page.title.lowercase()
            keywordsLower.count { kw -> titleLower.contains(kw) }
        }
        log.info(
            "Re-rank (kw={}): {}",
            keywords,
            reRanked.take(3).joinToString { "\"${it.title}\"[${keywordsLower.count { kw -> it.title.lowercase().contains(kw) }}]" },
        )
        return reRanked
    }

    private fun combineAndRank(
        titleResults: List<ConfluencePageRef>,
        textResults: List<ConfluencePageRef>,
        expandedResults: List<ConfluencePageRef>,
        ragResults: List<SearchResult>,
        keywordResults: List<ConfluencePageRef>,
        topK: Int,
    ): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val deduplicated = mutableListOf<SearchResult>()

        titleResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.TITLE_MATCH)) }
        expandedResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.SPACE_EXPANSION)) }
        textResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.TEXT_MATCH)) }
        ragResults.forEach { if (seen.add(it.pageId)) deduplicated.add(it) }
        keywordResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.KEYWORD_AND)) }

        return deduplicated.sortedByDescending { it.stage.score }.take(topK)
    }

    private fun ConfluencePageRef.toSearchResult(stage: SearchStage) = SearchResult(
        pageId = id, title = title, url = webUrl, snippet = excerpt, stage = stage,
    )

    suspend fun search(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
    ): String {
        val results = searchStructured(query, synonyms, topK, dateAfter, dateBefore, originalQuestion)
        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (query: $query)"
        return results.formatForSlack()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
        private const val RAG_TIMEOUT_MS = 5_000L

        // 대화형 접미사 제거
        private val SUFFIXES = listOf(
            "알려줘", "알려주세요", "알려 줘", "알려 주세요",
            "어디서 봐?", "어디서 봐", "어디 있어?", "어디 있어",
            "어떻게 돼?", "어떻게 돼", "뭐야?", "뭐야",
            "찾아줘", "보여줘", "설명해줘", "정리해줘",
            "궁금해", "궁금한데", "궁금해요", "궁금합니다",
        )

        /** 쿼리에서 유의미한 키워드 추출 (keyword AND fallback용) */
        internal fun extractSignificantKeywords(query: String): List<String> {
            val stopwords = setOf(
                "의", "를", "은", "는", "이", "가", "에", "도", "로", "와", "과", "을",
                "그", "저", "이것", "저것", "어떻게", "무엇", "하는", "하는가", "합니다",
                "관련", "정보", "내용", "문서", "자료", "현황", "대한", "위한", "통한",
                "Android", "android", "Kotlin", "kotlin",
            )
            return query.split("\\s+".toRegex())
                .map { it.trim() }
                .filter { it.length >= 2 && it !in stopwords }
                .distinct()
                .take(4) // AND 조건이 너무 많으면 결과 없음
        }

        /**
         * 구어체 질문 여부 판단 — true이면 keyword OR, false이면 AND
         *
         * 조건: 키워드 중 조사/어미로 끝나는 토큰이 있거나, 유효 키워드 비율이 낮으면 구어체로 판단
         */
        internal fun isConversationalQuery(keywords: List<String>, cleanedQuery: String): Boolean {
            val noisySuffixes = listOf("이", "가", "을", "를", "은", "는", "는지", "하는지", "는가", "해", "해요")
            val hasNoisyToken = keywords.any { k -> noisySuffixes.any { k.endsWith(it) } }
            val tokenCount = cleanedQuery.split("\\s+".toRegex()).size
            val keywordRatio = if (tokenCount > 0) keywords.size.toFloat() / tokenCount else 1f
            return hasNoisyToken || keywordRatio < 0.6f
        }

        /** CQL 검색 전 쿼리 정제: 특수문자 제거 + 대화형 접미사 제거 */
        internal fun cleanQuery(query: String): String {
            var q = query
            // 대화형 접미사 제거
            for (s in SUFFIXES) {
                if (q.endsWith(s)) {
                    q = q.removeSuffix(s).trimEnd()
                    break
                }
            }
            // CQL을 깨뜨리는 특수문자 제거 (내용은 유지)
            q = q.replace(Regex("[\\[\\]|~{}()]"), " ")
            // 언더스코어 → 공백 (제목에서 구분자로 사용)
            q = q.replace('_', ' ')
            // 연속 공백 정리
            q = q.replace(Regex("\\s+"), " ").trim()
            return q.ifBlank { query.trim() }
        }
    }
}
