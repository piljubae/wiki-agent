package io.github.veronikapj.wiki.jira

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

data class JiraConfluenceRef(val pageId: String, val title: String, val url: String)

data class JiraIssue(
    val key: String,
    val summary: String,
    val status: String,
    val type: String,
    val assignee: String,
    val description: String,
    val recentComments: List<String>,
    val confluenceRefs: List<JiraConfluenceRef>,
)

class JiraClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 이슈 조회 실패(404 등) → null. 댓글/원격링크 실패는 무시(빈값). */
    suspend fun getIssue(key: String): JiraIssue? {
        val issueJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key?fields=summary,status,issuetype,assignee,description")
        }.getOrElse { log.warn("Jira getIssue {} failed: {}", key, it.message); return null }
        val commentsJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key/comment?orderBy=-created&maxResults=3")
        }.getOrDefault("")
        val remoteJson = runCatching {
            httpGet("$baseUrl/rest/api/2/issue/$key/remotelinks")
        }.getOrDefault("")
        return runCatching { parseIssue(key, issueJson, commentsJson, remoteJson) }
            .getOrElse { log.warn("Jira parse {} failed: {}", key, it.message); null }
    }

    private suspend fun httpGet(url: String): String =
        httpClient.get(url) {
            header("Authorization", "Basic $token")
            header("Accept", "application/json")
        }.bodyAsText()

    internal fun parseIssue(key: String, issueJson: String, commentsJson: String, remoteLinksJson: String): JiraIssue {
        val root = jsonParser.parseToJsonElement(sanitize(issueJson)).jsonObject
        val fields = root["fields"]?.jsonObject ?: JsonObject(emptyMap())
        val summary = fields["summary"]?.jsonPrimitive?.contentOrNull ?: ""
        val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val type = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        val assigneeEl = fields["assignee"]
        val assignee = if (assigneeEl == null || assigneeEl is JsonNull) "" else assigneeEl.jsonObject.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
        val descEl = fields["description"]
        val description = if (descEl == null || descEl is JsonNull) "" else descEl.jsonPrimitive.contentOrNull ?: ""
        return JiraIssue(
            key = key,
            summary = summary,
            status = status,
            type = type,
            assignee = assignee,
            description = description,
            recentComments = parseComments(commentsJson),
            confluenceRefs = extractConfluenceRefs(description, remoteLinksJson),
        )
    }

    internal fun parseComments(commentsJson: String): List<String> {
        if (commentsJson.isBlank()) return emptyList()
        return runCatching {
            val arr = jsonParser.parseToJsonElement(sanitize(commentsJson)).jsonObject["comments"]?.jsonArray
                ?: return emptyList()
            arr.take(3).map { c ->
                val o = c.jsonObject
                val author = o["author"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: ""
                val body = o["body"]?.jsonPrimitive?.contentOrNull ?: ""
                if (author.isNotBlank()) "$author: $body" else body
            }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    internal fun extractConfluenceRefs(description: String, remoteLinksJson: String): List<JiraConfluenceRef> {
        val refMap = mutableMapOf<String, JiraConfluenceRef>()
        PAGE_REGEX.findAll(description).forEach { m ->
            val full = m.value
            val pageId = m.groupValues[1]
            refMap[pageId] = JiraConfluenceRef(
                pageId = pageId,
                title = "",
                url = if (full.startsWith("http")) full else baseUrl + full,
            )
        }
        runCatching {
            val arr = jsonParser.parseToJsonElement(sanitize(remoteLinksJson)).jsonArray
            arr.forEach { el ->
                val obj = el.jsonObject["object"]?.jsonObject ?: return@forEach
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val m = PAGE_REGEX.find(url) ?: return@forEach
                val pageId = m.groupValues[1]
                // Update existing ref with title if available, or add new one
                refMap[pageId] = JiraConfluenceRef(pageId = pageId, title = title, url = url)
            }
        }
        return refMap.values.toList()
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\p{Cntrl}&&[^\\r\\n]]"), " ")

    companion object {
        private val log = LoggerFactory.getLogger(JiraClient::class.java)
        private val PAGE_REGEX = Regex("""(?:https?://[^\s"')\]]+)?/wiki/[^\s"')\]]*?/pages/(\d+)""")
    }
}
