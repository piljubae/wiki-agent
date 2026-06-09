package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.github.GitHubWikiClient
import org.slf4j.LoggerFactory

class GitHubWikiSearchAgent(
    private val client: GitHubWikiClient,
    private val repos: List<String>,
) {
    suspend fun search(query: String): String {
        log.info("GitHub Wiki search: query='{}', repos={}", query, repos)
        val pages = client.searchPages(query, repos)
        if (pages.isEmpty()) return "관련 GitHub Wiki 문서를 찾을 수 없습니다. (query: $query)"

        return buildString {
            appendLine("*\"$query\"* 관련 GitHub 문서 (${pages.size}건):\n")
            pages.forEachIndexed { i, page ->
                val content = if (page.snippet.isNotBlank()) {
                    page.snippet
                } else {
                    val isWiki = !page.htmlUrl.contains("/blob/")
                    val rawUrl = client.buildRawUrl(page.repoFullName, page.path, isWiki)
                    client.fetchContent(rawUrl).lines().take(5).joinToString("\n").take(300)
                }
                appendLine("${i + 1}. *${page.title}* (${page.repoFullName})")
                if (page.htmlUrl.isNotBlank()) appendLine("   <${page.htmlUrl}|링크>")
                if (content.isNotBlank()) appendLine("   > ${content.replace("\n", "\n   > ")}")
                appendLine()
            }
        }.trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(GitHubWikiSearchAgent::class.java)
    }
}
