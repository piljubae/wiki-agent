package io.github.veronikapj.wiki.rag

import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.RagConfig
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import org.slf4j.LoggerFactory

class VectorIndexAgent(
    private val confluenceClient: ConfluenceClient,
    private val chromaClient: ChromaClient,
    private val llmExpandClient: LlmExpandClient?,
    private val googleEmbeddingClient: GoogleEmbeddingClient?,
    private val config: RagConfig,
    private val spaces: List<String>,
    private val collectionName: String = "wiki-pages",
    private val topK: Int = 100,
) {
    suspend fun indexAll(): Int {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        val pages = confluenceClient.listPages(spaces, limit = topK)
        log.info("Indexing {} pages into ChromaDB (mode={})", pages.size, config.embeddingMode)

        var indexed = 0
        pages.chunked(10).forEach { batch ->
            val ids = mutableListOf<String>()
            val docs = mutableListOf<String>()
            val embeddings = mutableListOf<List<Float>>()
            val metas = mutableListOf<Map<String, String>>()

            batch.forEach { ref ->
                runCatching {
                    // excerpt 기반 인덱싱 — fetchPageContent의 regex 파싱 실패 방지
                    val text = "${ref.title}\n${ref.excerpt}".take(2048)
                    if (text.isBlank()) return@forEach
                    ids += ref.id
                    docs += when (config.embeddingMode) {
                        EmbeddingMode.LLM_EXPAND ->
                            requireNotNull(llmExpandClient).enrichDocument(text)
                        EmbeddingMode.GOOGLE_EMBEDDING -> text
                    }
                    if (config.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING) {
                        embeddings += requireNotNull(googleEmbeddingClient).embed(text)
                    }
                    metas += mapOf("title" to ref.title, "url" to ref.webUrl)
                    indexed++
                }.onFailure { log.warn("Failed to index page {}: {}", ref.id, it.message) }
            }

            if (ids.isNotEmpty()) {
                chromaClient.addDocuments(
                    collectionId = collectionId,
                    ids = ids,
                    documents = docs,
                    embeddings = if (embeddings.isNotEmpty()) embeddings else null,
                    metadatas = metas,
                )
            }
        }
        log.info("Indexed {} pages", indexed)
        return indexed
    }

    companion object {
        private val log = LoggerFactory.getLogger(VectorIndexAgent::class.java)
    }
}
