package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.slf4j.LoggerFactory

data class ChromaQueryResult(
    val id: String,
    val document: String,
    val metadata: Map<String, String>,
    val distance: Float,
)

class ChromaClient(
    private val baseUrl: String,
    private val tenant: String = "default_tenant",
    private val database: String = "default_database",
) {

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    private val apiBase get() = "$baseUrl/api/v2/tenants/$tenant/databases/$database"

    suspend fun getOrCreateCollection(name: String): String {
        val body = """{"name":"$name","get_or_create":true}"""
        val response = httpClient.post("$apiBase/collections") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
            ?: error("Cannot parse collection id from: $response")
    }

    suspend fun addDocuments(
        collectionId: String,
        ids: List<String>,
        documents: List<String>,
        embeddings: List<List<Float>>? = null,
        metadatas: List<Map<String, String>> = emptyList(),
    ) {
        val body = buildAddBody(ids, documents, embeddings, metadatas)
        log.debug("addDocuments to {}: {} docs", collectionId, ids.size)
        httpClient.post("$apiBase/collections/$collectionId/add") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun upsertDocuments(
        collectionId: String,
        ids: List<String>,
        documents: List<String>,
        embeddings: List<List<Float>>? = null,
        metadatas: List<Map<String, String>> = emptyList(),
    ) {
        val body = buildAddBody(ids, documents, embeddings, metadatas)
        log.debug("upsertDocuments to {}: {} docs", collectionId, ids.size)
        httpClient.post("$apiBase/collections/$collectionId/upsert") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun query(
        collectionId: String,
        queryTexts: List<String>? = null,
        queryEmbeddings: List<List<Float>>? = null,
        nResults: Int = 5,
    ): List<ChromaQueryResult> {
        val body = buildQueryBody(queryTexts, queryEmbeddings, nResults)
        val response = httpClient.post("$apiBase/collections/$collectionId/query") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return parseQueryResults(response)
    }

    internal fun buildAddBody(
        ids: List<String>,
        documents: List<String>,
        embeddings: List<List<Float>>?,
        metadatas: List<Map<String, String>>,
    ): String = buildString {
        append("{")
        append("\"ids\":[${ids.joinToString(",") { "\"$it\"" }}]")
        append(",\"documents\":[${documents.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        if (embeddings != null) {
            append(",\"embeddings\":[${embeddings.joinToString(",") { vec -> "[${vec.joinToString(",")}]" }}]")
        }
        if (metadatas.isNotEmpty()) {
            append(",\"metadatas\":[${metadatas.joinToString(",") { meta ->
                "{${meta.entries.joinToString(",") { (k, v) -> "\"$k\":\"${v.escapeJson()}\"" }}}"
            }}]")
        }
        append("}")
    }

    internal fun buildQueryBody(
        queryTexts: List<String>?,
        queryEmbeddings: List<List<Float>>?,
        nResults: Int,
    ): String = buildString {
        append("{")
        if (queryTexts != null) {
            append("\"query_texts\":[${queryTexts.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        }
        if (queryEmbeddings != null) {
            val sep = if (queryTexts != null) "," else ""
            append("${sep}\"query_embeddings\":[${queryEmbeddings.joinToString(",") { vec -> "[${vec.joinToString(",")}]" }}]")
        }
        val sep = if (queryTexts != null || queryEmbeddings != null) "," else ""
        append("${sep}\"n_results\":$nResults")
        append("}")
    }

    internal fun parseQueryResults(json: String): List<ChromaQueryResult> {
        val ids = Regex("\"ids\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(",")
            ?.map { it.trim().trim('"') } ?: return emptyList()
        val docs = Regex("\"documents\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(Regex(",(?=\")"))
            ?.map { it.trim().trim('"') } ?: emptyList()
        val distances = Regex("\"distances\"\\s*:\\s*\\[\\[([^]]+)]]").find(json)
            ?.groupValues?.get(1)?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()

        val metaBlock = Regex("\"metadatas\"\\s*:\\s*\\[\\[(.+?)]]", RegexOption.DOT_MATCHES_ALL).find(json)
            ?.groupValues?.get(1) ?: ""
        val metas = Regex("\\{([^}]*)\\}").findAll(metaBlock).map { m ->
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(m.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()

        return ids.mapIndexed { i, id ->
            ChromaQueryResult(
                id = id,
                document = docs.getOrElse(i) { "" },
                metadata = metas.getOrElse(i) { emptyMap() },
                distance = distances.getOrElse(i) { 1f },
            )
        }
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ChromaClient::class.java)
    }
}

private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
