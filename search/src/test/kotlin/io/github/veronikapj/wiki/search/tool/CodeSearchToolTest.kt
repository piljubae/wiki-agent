package io.github.veronikapj.wiki.search.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실제 쿼리 플로우 기반 테스트.
 *
 * 원본 쿼리: "클라이언트에서 *API 호출 시 직접 세팅해서 보내는* custom request header 모두 찾아서 전부 보여줘"
 *
 * 플로우:
 *   ① expandQuery()  → 한+영 혼합 확장 텍스트
 *   ② ChromaDB       → embeddingFn(expandedQuery) → queryEmbeddings (queryTexts fallback 제거됨)
 *   ③ BM25           → 원본 쿼리로 키워드 매칭
 *   ④ grep           → buildGrepPatterns(expandedQuery) → 영문 식별자
 *   ⑤ GitHub fallback → extractEnglishKeywords(expandedQuery) → 영문만 → URL 인코딩
 */
class CodeSearchToolTest {

    // expandQuery가 반환할 법한 실제 결과 (로그에서 관찰된 패턴)
    private val realExpandedQuery =
        "클라이언트에서 API 호출 시 직접 세팅해서 보내는 custom request header " +
        "클라이언트 API 호출 직접 세팅 custom request header " +
        "client HTTP headers manual headers explicit headers user-defined headers " +
        "application headers Authorization User-Agent Content-Type X-headers " +
        "Bearer token API key headers interceptors middleware retrofit headers " +
        "axios headers okhttp headers fetch headers 헤더 요청 앱 네트워크 REST " +
        "사용자정의 수동 설정 인터셉터 미들웨어 configure add enumerate list show display find search"

    // ── ⑤ extractEnglishKeywords: expandedQuery → GitHub search용 영문 키워드 ──

    @Test
    fun `실제 쿼리 — expandedQuery에서 영문 코드 키워드 추출`() {
        val result = CodeSearchTool.extractEnglishKeywords(realExpandedQuery)
        // 코드 검색에 유용한 영문 키워드가 포함되어야 함 (take(8) 범위 내)
        assertTrue(result.contains("custom"), "custom 포함")
        assertTrue(result.contains("request"), "request 포함")
        assertTrue(result.contains("header"), "header 포함")
        assertTrue(result.contains("client"), "client 포함")
        assertTrue(result.contains("HTTP"), "HTTP 포함")
        // 한글은 제외
        assertTrue(!result.contains("클라이언트"), "한글 제외")
        assertTrue(!result.contains("헤더"), "한글 제외")
        assertTrue(!result.contains("인터셉터"), "한글 제외")
    }

    @Test
    fun `실제 쿼리 — stopword가 GitHub search에 안 들어감`() {
        val result = CodeSearchTool.extractEnglishKeywords(realExpandedQuery)
        // expandQuery가 반환한 영문 중 stopword는 제거
        assertTrue(!result.contains("configure"), "configure는 stopword")
        assertTrue(!result.contains("enumerate"), "enumerate는 stopword")
        assertTrue(!result.contains("display"), "display는 stopword")
        assertTrue(!result.contains("find"), "find는 stopword")
        assertTrue(!result.contains("search"), "search는 stopword")
    }

    @Test
    fun `실제 쿼리 — 원본 한글만 있으면 GitHub search 스킵`() {
        val koreanOnly = "클라이언트에서 호출 시 직접 세팅해서 보내는 헤더 요청 앱"
        val result = CodeSearchTool.extractEnglishKeywords(koreanOnly)
        assertEquals("", result, "영문 키워드 없으면 빈 문자열 → GitHub search 스킵")
    }

    // ── 엣지 케이스 ──

    @Test
    fun `특수문자 포함 쿼리 — 별표 등 제거 후 영문 추출`() {
        // expandQuery 전 원본에 *가 있는 경우 — extractEnglishKeywords에 들어올 일은 없지만 방어
        val withSpecials = "*API 호출 custom* request \"header\" (test)"
        val result = CodeSearchTool.extractEnglishKeywords(withSpecials)
        // *가 붙은 *API는 영문 패턴에 매칭 안 됨
        assertTrue(!result.contains("*"))
    }

    @Test
    fun `PascalCase, camelCase 키워드도 추출`() {
        val expanded = "OkHttpClient addHeader addInterceptor networkInterceptors 헤더 추가"
        val result = CodeSearchTool.extractEnglishKeywords(expanded)
        assertTrue(result.contains("OkHttpClient"))
        assertTrue(result.contains("addHeader"))
        assertTrue(result.contains("addInterceptor"))
        assertTrue(result.contains("networkInterceptors"))
    }

    @Test
    fun `최대 8개 제한`() {
        val keywords = CodeSearchTool.extractEnglishKeywords(realExpandedQuery).split(" ")
        assertTrue(keywords.size <= 8, "최대 8개: actual=${keywords.size}")
    }

    @Test
    fun `중복 제거`() {
        val expanded = "header header headers Authorization Authorization client client"
        val keywords = CodeSearchTool.extractEnglishKeywords(expanded).split(" ")
        assertEquals(keywords.distinct(), keywords, "중복 없어야 함")
    }
}
