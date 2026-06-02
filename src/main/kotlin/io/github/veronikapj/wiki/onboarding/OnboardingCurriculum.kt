package io.github.veronikapj.wiki.onboarding

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class OnboardingCurriculum(
    val lastUpdated: String,
    val phases: List<CurriculumPhase>,
)

@Serializable
data class CurriculumPhase(
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
data class ContentSource(
    val type: String,
    val path: String? = null,
    val query: String? = null,
)

object CurriculumLoader {
    private val yaml = Yaml(configuration = YamlConfiguration(
        strictMode = false,
    ))

    fun load(path: String): OnboardingCurriculum {
        val text = File(path).readText()
        return yaml.decodeFromString(OnboardingCurriculum.serializer(), text)
    }
}
