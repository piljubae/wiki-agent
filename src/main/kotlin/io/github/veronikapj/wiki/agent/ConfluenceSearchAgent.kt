package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.confluence.ConfluencePageRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
    private val sufficientThreshold: Int = 3,
) {
    private data class CacheEntry(
        val results: List<SearchResult>,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired() = System.currentTimeMillis() - createdAt > TTL_MS
    }

    private val queryCache = object : LinkedHashMap<String, CacheEntry>(CACHE_MAX_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>) = size > CACHE_MAX_SIZE
    }

    @Synchronized
    private fun getCached(key: String): List<SearchResult>? {
        val entry = queryCache[key] ?: return null
        if (entry.isExpired()) {
            queryCache.remove(key)
            return null
        }
        return entry.results
    }

    @Synchronized
    private fun putCache(key: String, results: List<SearchResult>) {
        queryCache[key] = CacheEntry(results)
    }

    suspend fun searchStructured(
        query: String, synonyms: List<String> = emptyList(), topK: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
        originalQuestion: String = "",
    ): List<SearchResult> {
        val cleaned = cleanQuery(query)
        // originalQuestion은 re-ranking에만 사용 → 캐시 키에서 제외해 동일 검색 쿼리 캐시 공유
        val cacheKey = "$cleaned|${synonyms.sorted().joinToString(",")}|$topK|$dateAfter|$dateBefore"
        getCached(cacheKey)?.let {
            log.info("Cache hit: query='{}'", cleaned)
            return reRankByOriginalQuestion(it, originalQuestion)
        }

        log.info("Searching: query='{}' → cleaned='{}', synonyms={}, spaces={}, dateAfter={}, dateBefore={}",
            query, cleaned, synonyms, spaces, dateAfter, dateBefore)

        // 1단계: 설정 스페이스에서 제목 검색 (re-rank 여유분을 위해 topK*2 fetch)
        val titleFetchLimit = topK * 2
        val titleResults = confluenceClient.searchByTitle(cleaned, spaces, synonyms, titleFetchLimit, dateAfter, dateBefore)
        log.info("Title search: {} results", titleResults.size)

        // Early return: 제목 매칭 충분하면 추가 검색 안 함
        if (titleResults.size >= sufficientThreshold) {
            log.info("Sufficient title matches ({}>={}), early return", titleResults.size, sufficientThreshold)
            log.info("Title results: {}", titleResults.take(5).joinToString { "\"${it.title}\"" })
            val rawEarlyResults = titleResults.map { it.toSearchResult(SearchStage.TITLE_MATCH) }
            val reRanked = reRankByOriginalQuestion(rawEarlyResults, originalQuestion).take(topK)
            putCache(cacheKey, reRanked)
            return reRanked
        }

        // 2단계: 부족 → 병렬로 text + 스페이스 확장
        log.info("Insufficient title matches ({}<{}), parallel fallback", titleResults.size, sufficientThreshold)
        val (textResults, expandedResults) = coroutineScope {
            val textDeferred = async {
                runCatching { confluenceClient.searchByText(cleaned, spaces, synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
            }
            val expandedDeferred = async {
                if (spaces.isNotEmpty()) {
                    runCatching { confluenceClient.searchByTitle(cleaned, emptyList(), synonyms, topK, dateAfter, dateBefore) }.getOrElse { emptyList() }
                } else emptyList()
            }
            Pair(textDeferred.await(), expandedDeferred.await())
        }

        // 3-1단계: keyword fallback (text search 0건 시) — AND 먼저, 0건이면 OR 재시도
        val keywordResults = if (textResults.isEmpty()) {
            val keywords = extractSignificantKeywords(cleaned)
            if (keywords.size >= 2) {
                log.info("Text search empty, trying keyword AND fallback: {}", keywords)
                val andResults = runCatching {
                    confluenceClient.searchByKeywords(keywords, spaces, topK, dateAfter, dateBefore, useOr = false)
                }.getOrElse { emptyList() }
                if (andResults.isNotEmpty()) {
                    andResults
                } else {
                    log.info("AND fallback empty, retrying with OR: {}", keywords)
                    runCatching {
                        confluenceClient.searchByKeywords(keywords, spaces, topK, dateAfter, dateBefore, useOr = true)
                    }.getOrElse { emptyList() }
                }
            } else emptyList()
        } else emptyList()
        log.info("Keyword fallback: {} results", keywordResults.size)

        // 3단계: 합산 + 중복 제거 + 랭킹
        val combined = combineAndRank(titleResults, textResults, expandedResults, keywordResults, topK)

        // 4단계: 전체 결과가 없으면 space 제한 없이 CQL 텍스트 검색 fallback
        val finalCombined = if (combined.isEmpty() && spaces.isNotEmpty()) {
            log.info("No results in configured spaces, falling back to global CQL search")
            val globalResults = runCatching {
                confluenceClient.searchByText(cleaned, emptyList(), synonyms, topK, dateAfter, dateBefore)
            }.getOrElse { emptyList() }
            log.info("Global fallback: {} results", globalResults.size)
            globalResults.map { it.toSearchResult(SearchStage.GLOBAL_FALLBACK) }
        } else combined

        putCache(cacheKey, finalCombined)
        return reRankByOriginalQuestion(finalCombined, originalQuestion)
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
        keywordResults: List<ConfluencePageRef>,
        topK: Int,
    ): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val deduplicated = mutableListOf<SearchResult>()

        titleResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.TITLE_MATCH)) }
        expandedResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.SPACE_EXPANSION)) }
        textResults.forEach { if (seen.add(it.id)) deduplicated.add(it.toSearchResult(SearchStage.TEXT_MATCH)) }
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
        private const val TTL_MS = 24 * 60 * 60 * 1_000L // 24 hours
        private const val CACHE_MAX_SIZE = 200

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
            )
            return query.split("\\s+".toRegex())
                .map { it.trim() }
                .filter { it.length >= 2 && it !in stopwords }
                .distinct()
                .take(4) // AND 조건이 너무 많으면 결과 없음
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
