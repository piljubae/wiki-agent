package io.github.veronikapj.wiki.context

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class Turn(val question: String, val answer: String)

class ConversationStore(private val sessionsDir: String = ".wiki/sessions") {

    private val json = Json { ignoreUnknownKeys = true }

    fun append(sessionId: String, question: String, answer: String) {
        val dir = File(sessionsDir)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$sessionId.jsonl")
        val ts = java.time.Instant.now().toString()
        file.appendText("""{"ts":"$ts","role":"user","content":${json.encodeToString(String.serializer(), question)}}""" + "\n")
        file.appendText("""{"ts":"$ts","role":"assistant","content":${json.encodeToString(String.serializer(), answer)}}""" + "\n")
    }

    fun loadAll(sessionId: String): List<Turn> {
        val file = File(sessionsDir, "$sessionId.jsonl")
        if (!file.exists()) return emptyList()

        val entries = file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val role = obj["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    role to content
                }.getOrNull()
            }

        val turns = mutableListOf<Turn>()
        var i = 0
        while (i < entries.size - 1) {
            val (userRole, userContent) = entries[i]
            val (assistantRole, assistantContent) = entries[i + 1]
            if (userRole == "user" && assistantRole == "assistant") {
                turns.add(Turn(userContent, assistantContent))
                i += 2
            } else {
                i++
            }
        }
        return turns
    }

    fun load(sessionId: String, maxTurns: Int = 5): List<Turn> = loadAll(sessionId).takeLast(maxTurns)

    fun loadSummary(sessionId: String): String? {
        val file = File(sessionsDir, "$sessionId.summary.md")
        if (!file.exists()) return null
        return file.readText().ifBlank { null }
    }

    fun saveSummary(sessionId: String, summary: String) {
        val dir = File(sessionsDir)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "$sessionId.summary.md").writeText(summary)
    }

    fun trimOldTurns(sessionId: String, keepRecent: Int) {
        val allTurns = loadAll(sessionId)
        if (allTurns.size <= keepRecent) return
        val recent = allTurns.takeLast(keepRecent)
        val file = File(sessionsDir, "$sessionId.jsonl")
        file.writeText("")
        for (turn in recent) {
            append(sessionId, turn.question, turn.answer)
        }
    }

    suspend fun compress(
        sessionId: String,
        summarizer: suspend (String) -> String,
        compressThreshold: Int = COMPRESS_THRESHOLD,
        keepRecent: Int = KEEP_RECENT,
    ) {
        val allTurns = loadAll(sessionId)
        if (allTurns.size <= compressThreshold) return

        val turnsToSummarize = allTurns.dropLast(keepRecent)

        val conversationText = buildString {
            loadSummary(sessionId)?.let {
                appendLine("이전 요약: $it")
                appendLine()
            }
            for (turn in turnsToSummarize) {
                appendLine("User: ${turn.question}")
                appendLine("Assistant: ${turn.answer}")
            }
        }

        val prompt = buildString {
            appendLine("다음은 Slack에서 사용자와 AI 어시스턴트의 대화입니다.")
            appendLine("핵심 내용을 3-5줄로 요약하세요. 검색한 문서명과 주요 답변 내용을 포함하세요.")
            appendLine()
            append(conversationText)
        }

        val summary = summarizer(prompt)
        saveSummary(sessionId, summary)
        trimOldTurns(sessionId, keepRecent)
    }

    companion object {
        const val COMPRESS_THRESHOLD = 10
        const val KEEP_RECENT = 4
    }
}
