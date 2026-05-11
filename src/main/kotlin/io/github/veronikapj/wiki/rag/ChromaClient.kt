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

    /** ChromaDB에 이미 존재하는 ID 집합 반환. 임베딩 캐시 체크에 사용. */
    suspend fun getExistingIds(collectionId: String, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val idsJson = ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val body = """{"ids":[$idsJson],"include":[]}"""
        val response = httpClient.post("$apiBase/collections/$collectionId/get") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        val matched = Regex("\"ids\"\\s*:\\s*\\[([^]]*)]").find(response)
            ?.groupValues?.get(1) ?: return emptySet()
        return Regex("\"([^\"]+)\"").findAll(matched).map { it.groupValues[1] }.toHashSet()
    }

    /**
     * 컬렉션 전체 문서의 id → lastModified 맵 반환. 증분 인덱싱 비교에 사용.
     * HTTP 오류 시 null 반환 (빈 컬렉션은 emptyMap 반환).
     */
    suspend fun getAllIdsWithLastModified(collectionId: String): Map<String, String>? {
        val body = """{"include":["metadatas"]}"""
        val response = runCatching {
            httpClient.post("$apiBase/collections/$collectionId/get") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.bodyAsText()
        }.getOrElse { e ->
            log.warn("getAllIdsWithLastModified failed: {}", e.message)
            return null
        }
        return parseIdsWithLastModified(response)
    }

    internal fun parseIdsWithLastModified(json: String): Map<String, String> {
        // ids: ["id1", "id2", ...]  — /get은 단일 배열
        val idsSection = Regex("\"ids\"\\s*:\\s*\\[([^]]*)]").find(json)
            ?.groupValues?.get(1) ?: return emptyMap()
        val idList = Regex("\"([^\"]+)\"").findAll(idsSection)
            .map { it.groupValues[1] }.toList()
        if (idList.isEmpty()) return emptyMap()

        // metadatas: [{...}, {...}]
        val metaSection = Regex("\"metadatas\"\\s*:\\s*\\[(.+?)](?=\\s*[,}])", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return emptyMap()
        val metaList = Regex("\\{([^}]*)}").findAll(metaSection).map { m ->
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(m.value)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()

        return idList.mapIndexedNotNull { i, id ->
            val lastMod = metaList.getOrNull(i)?.get("lastModified") ?: return@mapIndexedNotNull null
            id to lastMod
        }.toMap()
    }

    /** file_path 메타데이터 기준으로 해당 파일의 모든 청크 ID 반환 */
    suspend fun getIdsByFilePath(collectionId: String, repo: String, filePath: String): List<String> {
        val where = """{"${"$"}and":[{"repo":{"${"$"}eq":"$repo"}},{"file_path":{"${"$"}eq":"${filePath.escapeJson()}"}}]}"""
        val body = """{"where":$where,"include":[]}"""
        val response = httpClient.post("$apiBase/collections/$collectionId/get") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        return parseGetIdsResponse(response)
    }

    /** 지정한 ID 목록을 ChromaDB에서 삭제 */
    suspend fun deleteByIds(collectionId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val idsJson = ids.joinToString(",") { "\"${it.escapeJson()}\"" }
        val body = """{"ids":[$idsJson]}"""
        httpClient.post("$apiBase/collections/$collectionId/delete") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        log.debug("deleteByIds: deleted {} chunks", ids.size)
    }

    internal fun parseGetIdsResponse(json: String): List<String> {
        val matched = Regex("\"ids\"\\s*:\\s*\\[\\[([^]]*)]").find(json)?.groupValues?.get(1)
        if (matched == null) {
            if (json.isNotBlank()) log.warn("parseGetIdsResponse: unexpected response shape — {}", json.take(200))
            return emptyList()
        }
        if (matched.isBlank()) return emptyList()
        return Regex("\"([^\"]+)\"").findAll(matched).map { it.groupValues[1] }.toList()
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
        val response = httpClient.post("$apiBase/collections/$collectionId/upsert") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        if (response.contains("error", ignoreCase = true)) {
            log.warn("upsertDocuments error response: {}", response.take(200))
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
