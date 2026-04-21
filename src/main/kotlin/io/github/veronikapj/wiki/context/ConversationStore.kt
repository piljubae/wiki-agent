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

    fun load(sessionId: String, maxTurns: Int = 5): List<Turn> {
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

        return turns.takeLast(maxTurns)
    }
}
