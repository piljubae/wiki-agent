package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.PersonaType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

class UserPersonaStore(private val filePath: String = ".wiki/user-personas.json") {

    private val personas = HashMap<String, PersonaType>()

    init { load() }

    fun get(userId: String): PersonaType? = personas[userId]

    @Synchronized
    fun set(userId: String, persona: PersonaType) {
        personas[userId] = persona
        save()
    }

    private fun load() {
        val file = File(filePath)
        if (!file.exists()) return
        runCatching {
            val obj = Json.parseToJsonElement(file.readText()).jsonObject
            obj.forEach { (userId, value) ->
                runCatching { personas[userId] = PersonaType.valueOf(value.jsonPrimitive.content) }
                    .onFailure { log.warn("Unknown persona for user={}: {}", userId, value) }
            }
        }.onFailure { log.warn("Failed to load user personas: {}", it.message) }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            val obj = buildJsonObject {
                personas.forEach { (userId, persona) -> put(userId, persona.name) }
            }
            file.writeText(obj.toString())
        }.onFailure { log.warn("Failed to save user personas: {}", it.message) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(UserPersonaStore::class.java)
    }
}
