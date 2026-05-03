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

    // Note: GitHub's /pulls endpoint does not support 'since' as a filter parameter
    // (unlike /issues). The since param is appended but ignored by the API.
    // Deduplication of already-indexed PRs is handled client-side in PrIndexAgent
    // via lastPrNumber comparison.
    suspend fun fetchRecentPrs(repo: String, since: String? = null): List<GithubPrInfo> {
        val url = buildString {
            append("https://api.github.com/repos/$repo/pulls?state=all&sort=updated&direction=desc&per_page=50")
            if (since != null) append("&since=$since")
        }
        val json = apiGet(url) ?: return emptyList()
        return runCatching { parsePrListJson(repo, json) }.getOrDefault(emptyList())
    }

    // Note: GitHub Code Search API indexes only the default branch — branch param is informational only
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

    internal fun parsePrListJson(repo: String, json: String): List<GithubPrInfo> {
        val results = mutableListOf<GithubPrInfo>()
        val numberPattern = Regex("\"number\"\\s*:\\s*(\\d+)")

        numberPattern.findAll(json).forEach { numMatch ->
            val number = numMatch.groupValues[1].toIntOrNull() ?: return@forEach
            // Extract a window around this number occurrence to find sibling fields
            val start = maxOf(0, numMatch.range.first - 2000)
            val end = minOf(json.length, numMatch.range.last + 2000)
            val window = json.substring(start, end)

            fun windowField(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(window)?.groupValues?.get(1) ?: ""

            val title = windowField("title")
            if (title.isBlank()) return@forEach  // skip non-PR number occurrences

            val login = Regex("\"login\"\\s*:\\s*\"([^\"]+)\"").find(window)?.groupValues?.get(1) ?: ""
            val state = windowField("state")
            val mergedAt = windowField("merged_at").ifBlank { null }
            val headRef = Regex("\"head\"[^}]*\"ref\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
                .find(window)?.groupValues?.get(1) ?: ""

            results += GithubPrInfo(
                repo = repo,
                number = number,
                title = title,
                body = "",
                state = state,
                merged = mergedAt != null,
                mergedAt = mergedAt,
                author = login,
                branch = headRef,
                changedFiles = emptyList(),
            )
        }
        return results.distinctBy { it.number }
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

    // Fetches all Kotlin file paths from the git tree API
    suspend fun fetchKotlinFilePaths(repo: String, branch: String): List<String> {
        val treeUrl = "https://api.github.com/repos/$repo/git/trees/$branch?recursive=1"
        val json = apiGet(treeUrl) ?: return emptyList()
        return Regex("\"path\"\\s*:\\s*\"([^\"]+\\.kt)\"")
            .findAll(json)
            .map { it.groupValues[1] }
            .filter { !it.contains("Test") && !it.contains("build/") && !it.contains("generated") }
            .toList()
    }

    // Fetches and base64-decodes a file from GitHub Contents API
    suspend fun fetchFileContent(repo: String, path: String, branch: String): String? {
        val url = "https://api.github.com/repos/$repo/contents/${path}?ref=$branch"
        val json = apiGet(url) ?: return null
        val encoded = Regex("\"content\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        return runCatching {
            String(java.util.Base64.getMimeDecoder().decode(encoded.replace("\\n", "\n")))
        }.getOrNull()
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(GitHubCodeClient::class.java)
    }
}
