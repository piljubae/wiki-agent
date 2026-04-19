package io.github.veronikapj.wiki.confluence

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import org.slf4j.LoggerFactory

data class ConfluencePageRef(
    val id: String,
    val title: String,
    val webUrl: String,
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

    fun buildCqlSearchUrl(query: String, spaces: List<String>, limit: Int = 5): String {
        val spaceCql = if (spaces.isNotEmpty())
            " AND space IN (${spaces.joinToString(",") { "\"$it\"" }})"
        else ""
        val cql = URLEncoder.encode("text ~ \"$query\"$spaceCql", "UTF-8")
        return "$baseUrl/wiki/rest/api/content/search?cql=$cql&limit=$limit&expand=body.storage"
    }

    fun buildPageUrl(pageId: String): String =
        "$baseUrl/wiki/rest/api/content/$pageId?expand=body.storage,version,title"

    suspend fun searchPages(query: String, spaces: List<String>, limit: Int = 5): List<ConfluencePageRef> {
        val url = buildCqlSearchUrl(query, spaces, limit)
        log.info("Confluence CQL search: {}", url)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parseSearchResults(response, baseUrl)
    }

    suspend fun fetchPageContent(pageId: String): ConfluencePage {
        val url = buildPageUrl(pageId)
        val response = httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()
        return parsePage(response, baseUrl)
    }

    fun parseSearchResults(json: String, baseUrlForLinks: String): List<ConfluencePageRef> {
        val results = mutableListOf<ConfluencePageRef>()
        val idPattern = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        val titlePattern = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")
        val webUiPattern = Regex("\"webui\"\\s*:\\s*\"([^\"]+)\"")

        val ids = idPattern.findAll(json).map { it.groupValues[1] }.toList()
        val titles = titlePattern.findAll(json).map { it.groupValues[1] }.toList()
        val webUis = webUiPattern.findAll(json).map { it.groupValues[1] }.toList()

        ids.forEachIndexed { i, id ->
            results.add(
                ConfluencePageRef(
                    id = id,
                    title = titles.getOrElse(i) { "Untitled" },
                    webUrl = "$baseUrlForLinks${webUis.getOrElse(i) { "" }}",
                )
            )
        }
        return results
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
        return ConfluencePage(id, title, convertHtmlToMarkdown(body), "$baseUrlForLinks$webUi")
    }

    fun convertHtmlToMarkdown(html: String): String =
        html
            .replace(Regex("<h1[^>]*>"), "# ").replace(Regex("</h1>"), "\n")
            .replace(Regex("<h2[^>]*>"), "## ").replace(Regex("</h2>"), "\n")
            .replace(Regex("<h3[^>]*>"), "### ").replace(Regex("</h3>"), "\n")
            .replace(Regex("<p[^>]*>"), "\n").replace("</p>", "\n")
            .replace(Regex("<br[^>]*/?>"), "\n")
            .replace(Regex("<strong[^>]*>|<b[^>]*>"), "**").replace(Regex("</strong>|</b>"), "**")
            .replace(Regex("<li[^>]*>"), "- ").replace("</li>", "\n")
            .replace(Regex("<code[^>]*>"), "`").replace("</code>", "`")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceClient::class.java)
    }
}
