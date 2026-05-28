package io.github.veronikapj.wiki.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        return jsonParser.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content
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
        val root = runCatching { jsonParser.parseToJsonElement(response).jsonObject }.getOrElse { return emptySet() }
        val idsArr = root["ids"]?.jsonArray ?: return emptySet()
        // ChromaDB v2 /get: ids 필터는 flat [...], where 필터는 nested [[...]] 가능 → 둘 다 처리
        val flat = idsArr.getOrNull(0)?.let { runCatching { it.jsonArray }.getOrNull() } ?: idsArr
        return flat.map { it.jsonPrimitive.content }.toHashSet()
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
        val root = runCatching { jsonParser.parseToJsonElement(json).jsonObject }.getOrElse { return emptyMap() }
        val idList = root["ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return emptyMap()
        if (idList.isEmpty()) return emptyMap()
        val metaList = root["metadatas"]?.jsonArray ?: return emptyMap()
        return idList.mapIndexedNotNull { i, id ->
            val lastMod = metaList.getOrNull(i)?.jsonObject?.get("lastModified")?.jsonPrimitive?.content
                ?: return@mapIndexedNotNull null
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
        val root = runCatching { jsonParser.parseToJsonElement(json).jsonObject }.getOrElse {
            if (json.isNotBlank()) log.warn("parseGetIdsResponse: unexpected response shape — {}", json.take(200))
            return emptyList()
        }
        // ChromaDB /get with where filter returns nested [[...]] in v2
        val innerArr = root["ids"]?.jsonArray?.getOrNull(0)
            ?.let { runCatching { it.jsonArray }.getOrNull() }
        if (innerArr == null) {
            if (json.isNotBlank()) log.warn("parseGetIdsResponse: unexpected response shape — {}", json.take(200))
            return emptyList()
        }
        return innerArr.map { it.jsonPrimitive.content }
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
        if (response.contains("\"error\"") || response.contains("\"message\"")) {
            log.error("upsertDocuments error: {}", response)
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
        
        if (response.contains("\"error\"") || response.contains("\"message\"")) {
            log.error("ChromaDB query error: {}", response)
            return emptyList()
        }
        
        return runCatching { parseQueryResults(response) }
            .onFailure { log.error("Failed to parse query results: {}", response) }
            .getOrDefault(emptyList())
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
        val root = jsonParser.parseToJsonElement(json).jsonObject
        // /query returns nested arrays [[...]] (one per query text)
        fun nested(key: String) = root[key]?.jsonArray?.getOrNull(0)
            ?.let { runCatching { it.jsonArray }.getOrNull() }

        val ids = nested("ids")?.map { it.jsonPrimitive.content } ?: return emptyList()
        val docs = nested("documents")?.map { it.jsonPrimitive.content } ?: emptyList()
        val distances = nested("distances")?.mapNotNull { it.jsonPrimitive.floatOrNull } ?: emptyList()
        val metas = nested("metadatas")?.map { metaEl ->
            metaEl.jsonObject.entries.associate { (k, v) ->
                k to runCatching { v.jsonPrimitive.content }.getOrElse { "" }
            }
        } ?: emptyList()

        return ids.mapIndexed { i, id ->
            ChromaQueryResult(
                id = id,
                document = docs.getOrElse(i) { "" },
                metadata = metas.getOrElse(i) { emptyMap() },
                distance = distances.getOrElse(i) { 1f },
            )
        }
    }

    suspend fun count(collectionId: String): Int {
        val response = runCatching {
            httpClient.get("$apiBase/collections/$collectionId/count").bodyAsText()
        }.getOrElse { return 0 }
        return response.trim().toIntOrNull() ?: 0
    }

    fun close() = httpClient.close()

    companion object {
        private val log = LoggerFactory.getLogger(ChromaClient::class.java)
        private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private fun String.escapeJson() = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
    .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), " ")
