package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.agent.SearchResult
import io.github.veronikapj.wiki.agent.SearchStage
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import org.slf4j.LoggerFactory

class VectorSearchAgent(
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val googleEmbeddingClient: GoogleEmbeddingClient?,
    private val config: RagConfig,
    private val collectionName: String = "wiki-pages",
) {
    suspend fun searchStructured(query: String, nResults: Int = 5): List<SearchResult> {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)

        val results = when (config.embeddingMode) {
            EmbeddingMode.LLM_EXPAND -> {
                val expanded = requireNotNull(llmExpandClient).expandQuery(query)
                log.info("LLM_EXPAND query: '{}' → '{}'", query, expanded.take(100))
                chromaClient.query(collectionId, queryTexts = listOf(expanded), nResults = nResults)
            }
            EmbeddingMode.GOOGLE_EMBEDDING -> {
                val embedding = requireNotNull(googleEmbeddingClient).embed(query)
                chromaClient.query(collectionId, queryEmbeddings = listOf(embedding), nResults = nResults)
            }
        }

        return results.map { r ->
            SearchResult(
                pageId = r.metadata["pageId"] ?: r.id,
                title = r.metadata["title"] ?: "Untitled",
                url = r.metadata["url"] ?: "",
                snippet = r.document.lines().take(3).joinToString(" ").take(200),
                stage = SearchStage.RAG,
            )
        }
    }

    suspend fun search(query: String, nResults: Int = 5): String {
        val results = searchStructured(query, nResults)
        if (results.isEmpty()) return "관련 문서를 찾을 수 없습니다. (RAG query: $query)"

        return buildString {
            appendLine("*\"$query\"* 관련 문서 (RAG, ${results.size}건):\n")
            results.forEachIndexed { i, r ->
                appendLine("${i + 1}. *${r.title}*")
                if (r.url.isNotBlank()) appendLine("   <${r.url}|링크>")
                appendLine("   > ${r.snippet}")
                appendLine()
            }
        }.trim()
    }

    companion object {
        private val log = LoggerFactory.getLogger(VectorSearchAgent::class.java)
    }
}
