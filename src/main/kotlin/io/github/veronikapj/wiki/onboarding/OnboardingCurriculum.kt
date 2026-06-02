package io.github.veronikapj.wiki.onboarding

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class OnboardingCurriculum(
    val lastUpdated: String,
    val phases: List<CurriculumStep>,
)

@Serializable
data class CurriculumStep(
    val id: String,
    val name: String,
    val phase: Int,
    val day: String,
    val skippable: Boolean = true,
    val levelFilter: LevelFilter? = null,
    val sources: List<ContentSource>,
)

@Serializable
data class LevelFilter(
    val skipWhen: Map<String, String> = emptyMap(),
)

@Serializable
enum class SourceType {
    @SerialName("static") STATIC,
    @SerialName("confluence") CONFLUENCE,
    @SerialName("code") CODE,
}

@Serializable
data class ContentSource(
    val type: SourceType,
    val path: String? = null,
    val query: String? = null,
)

object CurriculumLoader {
    private val log = LoggerFactory.getLogger(CurriculumLoader::class.java)
    private val yaml = Yaml(configuration = YamlConfiguration(
        strictMode = false,
    ))

    fun load(path: String): OnboardingCurriculum? {
        val file = File(path)
        if (!file.exists()) {
            log.warn("Curriculum file not found: {}", path)
            return null
        }
        return runCatching {
            yaml.decodeFromString(OnboardingCurriculum.serializer(), file.readText())
        }.onFailure { e ->
            log.error("Failed to parse curriculum: {}", e.message)
        }.getOrNull()
    }
}
