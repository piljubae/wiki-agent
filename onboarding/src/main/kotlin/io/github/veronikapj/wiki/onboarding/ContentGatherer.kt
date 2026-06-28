package io.github.veronikapj.wiki.onboarding

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 온보딩 단계/질문에 대한 콘텐츠를 결정적으로 멀티소스에서 수집한다.
 * LLM 자율 호출 없이 step.sources / 질문에서 쿼리를 도출한다.
 */
internal class ContentGatherer(
    private val confluenceClient: ConfluenceClient?,
    private val confluenceTool: ConfluenceTool,
    private val codeSearchTool: CodeSearchTool,
    private val codeClient: GitHubCodeClient?,
    private val codeRepo: String?,
    private val codeBranch: String,
    private val wikiPageId: String?,
    private val onboardingSpace: String? = null,
) {
    private val log = LoggerFactory.getLogger(ContentGatherer::class.java)

    enum class Provenance(val emoji: String, val display: String) {
        WIKI("📄", "위키"),
        CODE("💻", "코드"),
        CONFLUENCE("🔗", "연관문서"),
        GITHUB_FILE("📁", "소스파일"),
    }

    enum class Platform { ANDROID, IOS, SHARED }

    data class GatheredContent(
        val label: String,
        val provenance: Provenance,
        val text: String,
        val platform: Platform = Platform.ANDROID,
    )

    // ── 단계 콘텐츠 ──

    fun gather(step: CurriculumStep): List<GatheredContent> {
        val out = mutableListOf<GatheredContent>()
        for (source in step.sources.take(MAX_SOURCES)) {
            runCatching {
                when (source.type) {
                    SourceType.CONFLUENCE_PAGE -> wikiSection(source)?.let { out += it }
                    SourceType.CODE -> codeContent(source.query)?.let { out += it }
                    SourceType.CONFLUENCE -> confluenceContent(source.query)?.let { out += it }
                    SourceType.GITHUB_FILE -> fileContent(source)?.let { out += it }
                    SourceType.STATIC -> log.warn("STATIC source ignored (wiki SSOT): {}", source.path)
                }
            }.onFailure { log.warn("gather source {} failed: {}", source.type, it.message) }
        }
        return out
    }

    // ── 질문 라이브 검색 ──

    fun gatherForQuestion(question: String, step: CurriculumStep?): List<GatheredContent> {
        val out = mutableListOf<GatheredContent>()

        // 현재 단계의 위키 섹션(맥락)
        if (step != null) {
            step.sources.firstOrNull { it.type == SourceType.CONFLUENCE_PAGE }?.let { src ->
                runCatching { wikiSection(src) }.getOrNull()?.let { out += it }
            }
        }
        runBlocking {
            val codeDeferred = async(Dispatchers.IO) {
                runCatching { codeContent(question) }.onFailure { log.warn("question codeSearch failed: {}", it.message) }.getOrNull()
            }
            val confDeferred = async(Dispatchers.IO) {
                runCatching { confluenceQuestionContent(question) }.onFailure { log.warn("question confluenceSearch failed: {}", it.message) }.getOrDefault(emptyList())
            }
            codeDeferred.await()?.let { out += it }
            out += confDeferred.await()
        }
        return out
    }

    /** 질문 경로 전용: onboardingSpace가 있으면 스코프 검색 + 플랫폼 라벨링, 없으면 기존 전체 검색 */
    private fun confluenceQuestionContent(query: String): List<GatheredContent> {
        val q = query.takeIf { it.isNotBlank() } ?: return emptyList()
        val space = onboardingSpace?.takeIf { it.isNotBlank() }
            ?: return confluenceContent(q)?.let { listOf(it) } ?: emptyList()
        return confluenceTool.searchScopedStructured(q, listOf(space)).take(QUESTION_CONFLUENCE_LIMIT).map { r ->
            GatheredContent(
                label = r.title,
                provenance = Provenance.CONFLUENCE,
                text = buildString {
                    if (r.snippet.isNotBlank()) appendLine(r.snippet)
                    append("(${r.url})")
                }.truncated(),
                platform = classifyPlatform(r.title, r.snippet),
            )
        }
    }

    // ── 소스별 수집 ──

    private fun codeContent(query: String?): GatheredContent? {
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val text = codeSearchTool.codeSearch(q)
        if (text.isBlank()) return null
        return GatheredContent(q, Provenance.CODE, text.truncated())
    }

    private fun confluenceContent(query: String?): GatheredContent? {
        val q = query?.takeIf { it.isNotBlank() } ?: return null
        val text = confluenceTool.confluenceSearch(q)
        if (text.isBlank()) return null
        return GatheredContent(q, Provenance.CONFLUENCE, text.truncated())
    }

    private fun fileContent(source: ContentSource): GatheredContent? {
        val client = codeClient ?: return null
        val path = source.path?.takeIf { it.isNotBlank() } ?: return null
        val repo = source.repo ?: codeRepo ?: return null
        val text = runBlocking { client.fetchFileContent(repo, path, codeBranch) }
        if (text.isNullOrBlank()) return null
        return GatheredContent(path, Provenance.GITHUB_FILE, text.truncated())
    }

    // ── 위키 섹션 (H2 파싱 + 캐시) ──

    private data class WikiSection(val title: String, val content: String)

    // All access is via @Synchronized loadWikiSections(); @Volatile is redundant here.
    private var wikiSectionsCache: List<WikiSection>? = null

    private fun wikiSection(source: ContentSource): GatheredContent? {
        val keyword = source.section?.takeIf { it.isNotBlank() } ?: return null
        val sections = loadWikiSections()
        val matched = sections.firstOrNull { it.title.contains(keyword, ignoreCase = true) }
        if (matched == null) {
            log.warn("Wiki section not found for '{}', available: {}", keyword, sections.map { it.title })
            return null
        }
        return GatheredContent(matched.title, Provenance.WIKI, matched.content.truncated())
    }

    @Synchronized
    private fun loadWikiSections(): List<WikiSection> {
        wikiSectionsCache?.let { return it }
        val client = confluenceClient ?: run { log.warn("confluenceClient null"); return emptyList() }
        val pageId = wikiPageId ?: run { log.warn("wikiPageId null"); return emptyList() }

        val html = runCatching { runBlocking { client.fetchPageRawHtml(pageId) } }
            .onFailure { log.error("Failed to fetch wiki page {}: {}", pageId, it.message) }
            .getOrDefault("")
        // Intentionally not cached: transient fetch failure self-heals on the next call.
        if (html.isBlank()) return emptyList()

        val sections = parseHtmlToSections(html)
        log.info("Loaded {} H2 sections from wiki page {}", sections.size, pageId)
        wikiSectionsCache = sections
        return sections
    }

    private fun parseHtmlToSections(html: String): List<WikiSection> {
        val h2Pattern = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        val h1h2Pattern = Regex("<h[12][^>]*>", RegexOption.DOT_MATCHES_ALL)
        val h2Matches = h2Pattern.findAll(html).toList()
        if (h2Matches.isEmpty()) {
            log.warn("No H2 headings in wiki HTML (length={})", html.length)
            return emptyList()
        }
        val h1h2Starts = h1h2Pattern.findAll(html).map { it.range.first }.toList()
        return h2Matches.map { match ->
            val title = match.groupValues[1].replace(Regex("<[^>]+>"), "").decodeHtmlEntities().trim()
            val sectionStart = match.range.last + 1
            val sectionEnd = h1h2Starts.firstOrNull { it > match.range.last } ?: html.length
            val sectionHtml = html.substring(sectionStart, sectionEnd)
            val plainText = sectionHtml
                .replace(Regex("<pre><code[^>]*>"), "\n```\n")
                .replace(Regex("</code></pre>"), "\n```\n")
                .replace(Regex("<code>"), "`").replace("</code>", "`")
                .replace(Regex("<strong>"), "*").replace("</strong>", "*")
                .replace(Regex("<h3[^>]*>"), "\n### ").replace(Regex("</h3>"), "\n")
                .replace(Regex("<li[^>]*>"), "\n• ").replace("</li>", "")
                .replace(Regex("<p[^>]*>"), "\n").replace("</p>", "\n")
                .replace(Regex("<br[^>]*/?>"), "\n")
                .replace(Regex("<tr>"), "\n").replace(Regex("<th[^>]*>"), "| ").replace(Regex("<td[^>]*>"), "| ")
                .replace(Regex("</t[hd]>"), " ")
                .replace(Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>")) { m -> "${m.groupValues[2]} (${m.groupValues[1]})" }
                .replace(Regex("<[^>]+>"), "")
                .decodeHtmlEntities()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            WikiSection(title, plainText)
        }
    }

    /** 위키 HTML 엔티티를 plain text로 디코딩. 제목·본문 공통. (&amp;는 마지막에 처리해 이중 디코딩 방지) */
    private fun String.decodeHtmlEntities(): String = this
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&mdash;", "—").replace("&ndash;", "–").replace("&nbsp;", " ")
        .replace("&amp;", "&")

    private fun String.truncated(): String =
        if (length <= MAX_CHARS) this else take(MAX_CHARS) + "\n…(이하 생략)"

    companion object {
        private const val MAX_SOURCES = 6
        private const val MAX_CHARS = 4000
        // 질문 경로 Confluence 결과 상한 — 프롬프트 비대화 방지 (각 블록은 MAX_CHARS로 별도 절단)
        private const val QUESTION_CONFLUENCE_LIMIT = 4

        // 라벨이 없어 제목+스니펫 토큰으로 플랫폼 분류. ANDROID가 기본값(android 토큰 불필요).
        private val IOS_TOKENS = Regex(
            """\b(ios|swift|swiftui|uikit|xcode|cocoapods|testflight|kurly-ios)\b|아이폰""",
            RegexOption.IGNORE_CASE)
        private val ANDROID_TOKENS = Regex(
            """\b(android|kotlin|compose|gradle|hilt|jetpack|kurly-android)\b|안드로이드""",
            RegexOption.IGNORE_CASE)

        fun classifyPlatform(title: String, snippet: String): Platform {
            val text = "$title\n$snippet"
            val ios = IOS_TOKENS.containsMatchIn(text)
            val android = ANDROID_TOKENS.containsMatchIn(text)
            return when {
                ios && android -> Platform.SHARED
                ios -> Platform.IOS
                else -> Platform.ANDROID
            }
        }

        fun formatBlocks(items: List<GatheredContent>): String =
            items.joinToString("\n\n") { gc ->
                val marker = when (gc.platform) {
                    Platform.IOS -> " [🍎 iOS 참조]"
                    Platform.SHARED -> " [🔀 Android·iOS 공통]"
                    Platform.ANDROID -> ""
                }
                "=== ${gc.provenance.emoji} ${gc.provenance.display}$marker: ${gc.label} ===\n${gc.text}"
            }
    }
}
