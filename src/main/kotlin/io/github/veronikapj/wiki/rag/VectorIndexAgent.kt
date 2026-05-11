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
    private val topK: Int = 2000,
) {
    suspend fun indexAll(): Int {
        val collectionId = chromaClient.getOrCreateCollection(collectionName)
        val allPages = confluenceClient.listAllPages(spaces, maxPages = topK)
        log.info("Confluence: {} pages total (mode={})", allPages.size, config.embeddingMode)

        // 기존 ChromaDB 메타데이터 조회
        val existingMeta = chromaClient.getAllIdsWithLastModified(collectionId)
        log.info("ChromaDB: {} pages already indexed", existingMeta.size)

        // 삭제된 페이지 제거
        val allPageIds = allPages.map { it.id }.toSet()
        val toDelete = existingMeta.keys - allPageIds
        if (toDelete.isNotEmpty()) {
            chromaClient.deleteByIds(collectionId, toDelete.toList())
            log.info("Removed {} deleted pages from index", toDelete.size)
        }

        // 신규 또는 변경된 페이지만 인덱싱
        val toIndex = allPages.filter { ref ->
            ref.lastModified.isNotEmpty() && existingMeta[ref.id] != ref.lastModified
        }
        log.info("Incremental: {}/{} pages to update", toIndex.size, allPages.size)

        var indexed = 0
        toIndex.chunked(10).forEach { batch ->
            val ids = mutableListOf<String>()
            val docs = mutableListOf<String>()
            val embeddings = mutableListOf<List<Float>>()
            val metas = mutableListOf<Map<String, String>>()

            batch.forEach { ref ->
                runCatching {
                    val page = confluenceClient.fetchPageContent(ref.id)
                    val text = when (config.embeddingMode) {
                        EmbeddingMode.LLM_EXPAND ->
                            requireNotNull(llmExpandClient).enrichDocument("${page.title}\n${page.content}")
                        EmbeddingMode.GOOGLE_EMBEDDING ->
                            "${page.title}\n${page.content}"
                    }
                    ids += ref.id
                    docs += text
                    if (config.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING) {
                        embeddings += requireNotNull(googleEmbeddingClient).embed(text)
                    }
                    metas += mapOf(
                        "title" to page.title,
                        "url" to page.webUrl,
                        "lastModified" to ref.lastModified,
                    )
                    indexed++
                }.onFailure { log.warn("Failed to index page {}: {}", ref.id, it.message) }
            }

            if (ids.isNotEmpty()) {
                chromaClient.upsertDocuments(
                    collectionId = collectionId,
                    ids = ids,
                    documents = docs,
                    embeddings = if (embeddings.isNotEmpty()) embeddings else null,
                    metadatas = metas,
                )
            }
        }
        log.info("Incremental index complete: {} updated, {} skipped, {} deleted",
            indexed, allPages.size - toIndex.size, toDelete.size)
        return indexed
    }

    companion object {
        private val log = LoggerFactory.getLogger(VectorIndexAgent::class.java)
    }
}
