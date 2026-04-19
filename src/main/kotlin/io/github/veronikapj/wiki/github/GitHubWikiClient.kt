package io.github.veronikapj.wiki.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory
import java.net.URLEncoder

data class GitHubWikiPage(
    val title: String,
    val repoFullName: String,
    val path: String,
    val htmlUrl: String,
    val snippet: String,
)

class GitHubWikiClient(private val token: String = "") {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
    }

    suspend fun searchPages(query: String, repos: List<String>): List<GitHubWikiPage> {
        if (repos.isEmpty()) return emptyList()
        val url = buildSearchUrl(query, repos)
        log.info("GitHub Wiki search: {}", url)
        val response = httpClient.get(url) {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.bodyAsText()
        return parseSearchResults(response)
    }

    suspend fun fetchContent(rawUrl: String): String {
        return runCatching {
            httpClient.get(rawUrl) {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.bodyAsText()
        }.getOrDefault("")
    }

    internal fun buildSearchUrl(query: String, repos: List<String>): String {
        val repoQuery = repos.joinToString("+") { "repo:${it}.wiki" }
        val q = URLEncoder.encode("$query $repoQuery", "UTF-8")
        return "https://api.github.com/search/code?q=$q&per_page=10"
    }

    internal fun buildRawUrl(repoFullName: String, path: String): String {
        val owner = repoFullName.substringBefore("/")
        val repo = repoFullName.substringAfter("/")
        val page = path.removeSuffix(".md")
        return "https://raw.githubusercontent.com/wiki/$owner/$repo/$page.md"
    }

    internal fun parseSearchResults(json: String): List<GitHubWikiPage> {
        val results = mutableListOf<GitHubWikiPage>()
        val itemsStart = json.indexOf("\"items\"")
        if (itemsStart == -1) return emptyList()
        val arrayStart = json.indexOf('[', itemsStart)
        if (arrayStart == -1) return emptyList()

        // Split items by walking the JSON array, collecting each top-level object
        val items = mutableListOf<String>()
        var depth = 0
        var itemStart = -1
        var i = arrayStart
        while (i < json.length) {
            when (json[i]) {
                '[' -> { depth++; if (depth == 1) { /* array open */ } }
                '{' -> { depth++; if (depth == 2) itemStart = i }
                '}' -> {
                    depth--
                    if (depth == 1 && itemStart != -1) {
                        items.add(json.substring(itemStart, i + 1))
                        itemStart = -1
                    }
                }
                ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }

        for (text in items) {
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: continue
            val path = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: name
            val htmlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            val repoFullName = Regex("\"full_name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            val title = name.removeSuffix(".md").replace("-", " ")
            results.add(GitHubWikiPage(title, repoFullName, path, htmlUrl, ""))
        }
        return results
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GitHubWikiClient::class.java)
    }
}
