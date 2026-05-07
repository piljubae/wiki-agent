package io.github.veronikapj.wiki.confluence

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

data class ConfluencePageRef(
    val id: String,
    val title: String,
    val webUrl: String,
    val excerpt: String = "",
    val titleMatched: Boolean = false,
)

data class ConfluencePage(
    val id: String,
    val title: String,
    val content: String,
    val webUrl: String,
)

class ConfluenceClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    fun buildTitleCqlSearchUrl(
        query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
    ): String {
        val spaceCql = if (spaces.isNotEmpty())
            " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
        else ""
        val dateCql = buildDateCql(dateAfter, dateBefore)
        val safeQuery = escapeCql(query)
        val titleClauses = mutableListOf("title ~ \"$safeQuery\"")
        synonyms.take(MAX_TEXT_CLAUSES - 1).forEach { s ->
            titleClauses.add("title ~ \"${escapeCql(s)}\"")
        }
        val cql = URLEncoder.encode("(${titleClauses.joinToString(" OR ")}) AND type = page$spaceCql$dateCql", "UTF-8")
        return "$baseUrl/wiki/rest/api/search?cql=$cql&limit=$limit&expand=content"
    }

    fun buildTextCqlSearchUrl(
        query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
    ): String {
        val spaceCql = if (spaces.isNotEmpty())
            " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
        else ""
        val dateCql = buildDateCql(dateAfter, dateBefore)
        val safeQuery = escapeCql(query)

        // 전체 구문을 첫 번째 절로 — 단어 분리 OR보다 정밀
        val textClauses = mutableListOf("text ~ \"$safeQuery\"")
        val remainingSlots = (MAX_TEXT_CLAUSES - textClauses.size).coerceAtLeast(0)
        synonyms.take(remainingSlots).forEach { s ->
            textClauses.add("text ~ \"${escapeCql(s)}\"")
        }

        val cql = URLEncoder.encode("(${textClauses.joinToString(" OR ")}) AND type = page$spaceCql$dateCql", "UTF-8")
        return "$baseUrl/wiki/rest/api/search?cql=$cql&limit=$limit&expand=content"
    }

    private fun buildDateCql(dateAfter: String?, dateBefore: String?): String = buildString {
        if (dateAfter != null) append(" AND lastModified >= \"$dateAfter\"")
        if (dateBefore != null) append(" AND lastModified <= \"$dateBefore\"")
    }

    private fun escapeCql(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")

    suspend fun listPages(spaces: List<String>, limit: Int = 100): List<ConfluencePageRef> {
        val spaceCql = if (spaces.isNotEmpty())
            " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
        else ""
        val cql = URLEncoder.encode("type = page$spaceCql ORDER BY lastModified DESC", "UTF-8")
        val url = "$baseUrl/wiki/rest/api/search?cql=$cql&limit=$limit&expand=content"
        log.info("Listing pages: {}", url)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parseSearchResults(response, baseUrl)
    }

    data class SpaceInfo(val key: String, val name: String, val type: String)

    suspend fun listSpaces(): List<SpaceInfo> {
        val allSpaces = mutableListOf<SpaceInfo>()
        var start = 0
        val pageSize = 500
        do {
            val url = "$baseUrl/wiki/rest/api/space?limit=$pageSize&start=$start"
            val response = httpClient.get(url) {
                header("Authorization", "Basic $token")
                header("Accept", "application/json")
            }.bodyAsText()
            val sanitized = response.replace(Regex("[\\p{Cntrl}&&[^\\r\\n]]"), " ")
            val rawSize = runCatching {
                val root = jsonParser.parseToJsonElement(sanitized).jsonObject
                val results = root["results"]?.jsonArray ?: return@runCatching 0
                results.forEach { element ->
                    val space = element.jsonObject
                    val key = space["key"]?.jsonPrimitive?.content ?: return@forEach
                    val type = space["type"]?.jsonPrimitive?.content ?: ""
                    if (type == "personal") return@forEach
                    val name = space["name"]?.jsonPrimitive?.content ?: key
                    allSpaces.add(SpaceInfo(key, name, type))
                }
                results.size // 필터 전 원본 사이즈로 페이지네이션 판단
            }.getOrElse { e ->
                log.warn("Failed to list spaces: {}", e.message)
                0
            }
            start += pageSize
        } while (rawSize >= pageSize)
        log.info("Listed {} spaces (personal excluded)", allSpaces.size)
        return allSpaces
    }

    fun buildPageUrl(pageId: String): String =
        "$baseUrl/wiki/rest/api/content/$pageId?expand=body.storage,version,title"

    suspend fun searchByTitle(
        query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
    ): List<ConfluencePageRef> {
        val url = buildTitleCqlSearchUrl(query, spaces, synonyms, limit, dateAfter, dateBefore)
        log.info("Confluence title search: {}", url)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parseSearchResults(response, baseUrl).map { it.copy(titleMatched = true) }
    }

    suspend fun searchByText(
        query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
    ): List<ConfluencePageRef> {
        val url = buildTextCqlSearchUrl(query, spaces, synonyms, limit, dateAfter, dateBefore)
        log.info("Confluence text search: {}", url)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parseSearchResults(response, baseUrl)
    }

    suspend fun searchPages(
        query: String, spaces: List<String>, synonyms: List<String> = emptyList(), limit: Int = 5,
        dateAfter: String? = null, dateBefore: String? = null,
    ): List<ConfluencePageRef> {
        val titleResults = searchByTitle(query, spaces, synonyms, limit, dateAfter, dateBefore)
        log.info("Title search: {} results", titleResults.size)
        if (titleResults.size >= limit) return titleResults.take(limit)

        val remaining = limit - titleResults.size
        val textResults = searchByText(query, spaces, synonyms, remaining, dateAfter, dateBefore)
        val titleIds = titleResults.map { it.id }.toSet()
        return titleResults + textResults.filter { it.id !in titleIds }
    }

    suspend fun fetchPageContent(pageId: String): ConfluencePage {
        val url = buildPageUrl(pageId)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parsePage(response, baseUrl)
    }

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSearchResults(json: String, baseUrlForLinks: String): List<ConfluencePageRef> {
        return runCatching {
            // Confluence API excerpt에 raw control characters 포함 → JSON 파서 실패 방지
            val sanitized = json.replace(Regex("[\\p{Cntrl}&&[^\\r\\n]]"), " ")
            val root = jsonParser.parseToJsonElement(sanitized).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()
            log.debug("Search returned {} results", results.size)
            results.mapNotNull { element ->
                val result = element.jsonObject
                // 결과 레벨 필드 (더 안정적)
                val resultTitle = result["title"]?.jsonPrimitive?.content
                val resultUrl = result["url"]?.jsonPrimitive?.content
                // content 블록 (id 추출)
                val content = result["content"]?.jsonObject
                val id = content?.get("id")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val title = resultTitle
                    ?: content["title"]?.jsonPrimitive?.content
                    ?: "Untitled"
                val webUi = content["_links"]?.jsonObject?.get("webui")?.jsonPrimitive?.content
                    ?: resultUrl
                    ?: ""
                val excerpt = result["excerpt"]?.jsonPrimitive?.content?.take(300) ?: ""
                ConfluencePageRef(id = id, title = title, webUrl = "$baseUrlForLinks/wiki$webUi", excerpt = excerpt)
            }
        }.getOrElse { e ->
            log.warn("Failed to parse search results: {}", e.message)
            emptyList()
        }
    }

    private fun parsePage(json: String, baseUrlForLinks: String): ConfluencePage {
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "unknown"
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "Untitled"
        val body = Regex("\"value\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", "\n")
            ?: ""
        val webUi = Regex("\"webui\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        return ConfluencePage(id, title, convertHtmlToSlackMrkdwn(body), "$baseUrlForLinks/wiki$webUi")
    }

    fun convertHtmlToSlackMrkdwn(html: String): String =
        html
            .replace(Regex("<h1[^>]*>"), "*").replace(Regex("</h1>"), "*\n")
            .replace(Regex("<h2[^>]*>"), "*").replace(Regex("</h2>"), "*\n")
            .replace(Regex("<h3[^>]*>"), "*").replace(Regex("</h3>"), "*\n")
            .replace(Regex("<p[^>]*>"), "\n").replace("</p>", "\n")
            .replace(Regex("<br[^>]*/?>"), "\n")
            .replace(Regex("<strong[^>]*>|<b[^>]*>"), "*").replace(Regex("</strong>|</b>"), "*")
            .replace(Regex("<em[^>]*>|<i[^>]*>"), "_").replace(Regex("</em>|</i>"), "_")
            .replace(Regex("<li[^>]*>"), "• ").replace("</li>", "\n")
            .replace(Regex("<code[^>]*>"), "`").replace("</code>", "`")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceClient::class.java)
        private const val MAX_TEXT_CLAUSES = 8
        private val STOPWORDS = setOf(
            "의", "를", "은", "는", "이", "가", "에", "도", "로", "와", "과", "을",
            "그", "저", "이것", "저것", "어떻게", "무엇", "하는", "하는가", "합니다",
        )
    }
}
