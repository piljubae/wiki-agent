package io.github.veronikapj.wiki.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

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
        val results = mutableListOf<GitHubWikiPage>()
        val keywords = query.lowercase().split(" ").filter { it.length > 1 }

        for (repo in repos) {
            // 1) GitHub Wiki (.wiki git repo) — wiki 페이지가 있을 때만 동작
            results += searchInRepo("$repo.wiki", keywords, isWiki = true)
            // 2) 메인 레포의 마크다운 파일 (README, docs/, etc.)
            results += searchInRepo(repo, keywords, isWiki = false)
        }

        return results.distinctBy { it.path }.take(5)
    }

    private suspend fun searchInRepo(
        repoFullName: String,
        keywords: List<String>,
        isWiki: Boolean,
    ): List<GitHubWikiPage> {
        val treeUrl = "https://api.github.com/repos/$repoFullName/git/trees/HEAD?recursive=1"
        log.info("Fetching file tree: {}", treeUrl)
        val treeJson = runCatching {
            httpClient.get(treeUrl) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.bodyAsText()
        }.getOrElse {
            log.warn("Tree fetch failed for {}: {}", repoFullName, it.message)
            return emptyList()
        }

        if (treeJson.contains("\"Not Found\"") || treeJson.contains("\"message\"")) {
            val msg = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(treeJson)?.groupValues?.get(1)
            log.info("Repo {} not accessible: {}", repoFullName, msg)
            return emptyList()
        }

        val mdPaths = parseMdFilePaths(treeJson)
        log.info("Found {} .md files in {}", mdPaths.size, repoFullName)

        val results = mutableListOf<GitHubWikiPage>()
        for (path in mdPaths.take(20)) {
            val rawUrl = buildRawUrl(repoFullName, path, isWiki)
            val content = runCatching { fetchContent(rawUrl) }.getOrDefault("")
            if (content.isBlank()) continue

            val contentLower = content.lowercase()
            if (keywords.any { contentLower.contains(it) }) {
                val title = path.substringAfterLast("/").removeSuffix(".md").replace("-", " ")
                val htmlUrl = if (isWiki) {
                    val (owner, repo) = repoFullName.removeSuffix(".wiki").split("/")
                    "https://github.com/$owner/$repo/wiki/${path.removeSuffix(".md")}"
                } else {
                    "https://github.com/$repoFullName/blob/main/$path"
                }
                val snippetLines = content.lines()
                    .firstOrNull { l -> keywords.any { l.lowercase().contains(it) } }
                    ?: content.lines().firstOrNull { it.isNotBlank() } ?: ""
                results.add(GitHubWikiPage(title, repoFullName, path, htmlUrl, snippetLines.take(200)))
            }
        }
        return results
    }

    suspend fun fetchContent(rawUrl: String): String {
        return runCatching {
            httpClient.get(rawUrl) {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.bodyAsText()
        }.getOrDefault("")
    }

    internal fun buildRawUrl(repoFullName: String, path: String, isWiki: Boolean = false): String {
        return if (isWiki) {
            val cleanRepo = repoFullName.removeSuffix(".wiki")
            val (owner, repo) = cleanRepo.split("/")
            "https://raw.githubusercontent.com/wiki/$owner/$repo/$path"
        } else {
            "https://raw.githubusercontent.com/$repoFullName/main/$path"
        }
    }

    internal fun parseMdFilePaths(treeJson: String): List<String> {
        val paths = mutableListOf<String>()
        val pathRegex = Regex("\"path\"\\s*:\\s*\"([^\"]+\\.md)\"")
        pathRegex.findAll(treeJson).forEach { match ->
            paths.add(match.groupValues[1])
        }
        return paths
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GitHubWikiClient::class.java)
    }
}
