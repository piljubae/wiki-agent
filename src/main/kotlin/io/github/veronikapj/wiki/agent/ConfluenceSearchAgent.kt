package io.github.veronikapj.wiki.agent

import io.github.veronikapj.wiki.confluence.ConfluenceClient
import org.slf4j.LoggerFactory

class ConfluenceSearchAgent(
    private val confluenceClient: ConfluenceClient,
    private val spaces: List<String>,
) {
    suspend fun search(query: String, topK: Int = 5): String {
        log.info("Searching Confluence: query='{}', spaces={}", query, spaces)

        val pages = confluenceClient.searchPages(query, spaces, topK)
        if (pages.isEmpty()) {
            return "관련 문서를 찾을 수 없습니다. (query: $query)"
        }

        val sb = StringBuilder()
        sb.appendLine("*\"$query\"* 관련 Confluence 문서 (${pages.size}건):\n")

        pages.forEachIndexed { i, ref ->
            runCatching {
                val page = confluenceClient.fetchPageContent(ref.id)
                val snippet = page.content.lines().take(5).joinToString("\n").take(300)
                sb.appendLine("${i + 1}. *${ref.title}*")
                sb.appendLine("   <${ref.webUrl}|링크>")
                sb.appendLine("   > ${snippet.replace("\n", "\n   > ")}")
                sb.appendLine()
            }.onFailure {
                sb.appendLine("${i + 1}. *${ref.title}*  <${ref.webUrl}|링크>")
                sb.appendLine()
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfluenceSearchAgent::class.java)
    }
}
