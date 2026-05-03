package io.github.veronikapj.wiki.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory

data class GithubPrInfo(
    val repo: String,
    val number: Int,
    val title: String,
    val body: String,
    val state: String,
    val merged: Boolean,
    val mergedAt: String?,
    val author: String,
    val branch: String,
    val changedFiles: List<String>,
)

data class GithubCodeResult(
    val repo: String,
    val filePath: String,
    val htmlUrl: String,
    val snippet: String,
)

class GitHubCodeClient(private val token: String = "") {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }

    suspend fun fetchPr(repo: String, prNumber: Int): GithubPrInfo? {
        val url = "https://api.github.com/repos/$repo/pulls/$prNumber"
        val json = apiGet(url) ?: return null
        return runCatching { parsePrJson(repo, prNumber, json) }.getOrNull()
    }

    suspend fun fetchPrDiff(repo: String, prNumber: Int, maxChars: Int = 2000): String {
        val url = "https://api.github.com/repos/$repo/pulls/$prNumber"
        val diff = runCatching {
            httpClient.get(url) {
                header("Accept", "application/vnd.github.v3.diff")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }.bodyAsText()
        }.getOrDefault("")
        return filterDiffLines(diff).take(maxChars)
    }

    suspend fun fetchRecentPrs(repo: String, since: String? = null): List<GithubPrInfo> {
        val url = buildString {
            append("https://api.github.com/repos/$repo/pulls?state=all&sort=updated&direction=desc&per_page=50")
            if (since != null) append("&since=$since")
        }
        val json = apiGet(url) ?: return emptyList()
        return runCatching { parsePrListJson(repo, json) }.getOrDefault(emptyList())
    }

    suspend fun searchCode(repo: String, query: String, branch: String = "develop"): List<GithubCodeResult> {
        val q = "$query+repo:$repo"
        val url = "https://api.github.com/search/code?q=${q.replace(" ", "+")}&per_page=10"
        val json = apiGet(url) ?: return emptyList()
        return runCatching { parseCodeSearchJson(repo, json) }.getOrDefault(emptyList())
    }

    fun parsePrUrl(url: String): Pair<String, Int>? {
        val regex = Regex("github\\.com/([^/]+/[^/]+)/pull/(\\d+)")
        val match = regex.find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2].toInt()
    }

    fun filterDiffLines(diff: String): String {
        val excludePatterns = listOf(
            "Podfile.lock", "package-lock.json", "yarn.lock",
            "build/generated", ".generated.", "/*.pb.swift",
        )
        val lines = diff.lines()
        val result = mutableListOf<String>()
        var skip = false
        for (line in lines) {
            if (line.startsWith("diff --git")) {
                skip = excludePatterns.any { line.contains(it) }
            }
            if (!skip) result += line
        }
        return result.joinToString("\n")
    }

    fun extractTicket(title: String, branch: String): String? {
        val pattern = Regex("[A-Z]+-\\d+")
        return pattern.find(title)?.value ?: pattern.find(branch)?.value
    }

    private suspend fun apiGet(url: String): String? {
        return runCatching {
            val response = httpClient.get(url) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            response.bodyAsText()
        }.onFailure { log.warn("GitHub API failed: {} — {}", url, it.message) }.getOrNull()
    }

    private fun parsePrJson(repo: String, number: Int, json: String): GithubPrInfo {
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        fun boolField(name: String) = Regex("\"$name\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1) == "true"

        val author = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
        val branch = Regex("\"head\".*?\"ref\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: ""
        val filesJson = runCatching {
            Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        }.getOrDefault(emptyList())

        return GithubPrInfo(
            repo = repo,
            number = number,
            title = field("title"),
            body = field("body").take(1000),
            state = field("state"),
            merged = boolField("merged"),
            mergedAt = field("merged_at").ifBlank { null },
            author = author,
            branch = branch,
            changedFiles = filesJson,
        )
    }

    private fun parsePrListJson(repo: String, json: String): List<GithubPrInfo> {
        val prBlocks = Regex("\\{[^{}]*\"number\"[^{}]*\\}").findAll(json)
        return prBlocks.mapNotNull { match ->
            runCatching {
                val block = match.value
                fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: ""
                val number = Regex("\"number\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                GithubPrInfo(
                    repo = repo, number = number,
                    title = field("title"), body = "",
                    state = field("state"),
                    merged = field("merged_at").isNotBlank(),
                    mergedAt = field("merged_at").ifBlank { null },
                    author = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: "",
                    branch = "", changedFiles = emptyList(),
                )
            }.getOrNull()
        }.toList()
    }

    private fun parseCodeSearchJson(repo: String, json: String): List<GithubCodeResult> {
        return Regex("\"path\"\\s*:\\s*\"([^\"]+)\".*?\"html_url\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .findAll(json)
            .take(10)
            .map { m ->
                GithubCodeResult(
                    repo = repo,
                    filePath = m.groupValues[1],
                    htmlUrl = m.groupValues[2],
                    snippet = "",
                )
            }.toList()
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GitHubCodeClient::class.java)
    }
}
