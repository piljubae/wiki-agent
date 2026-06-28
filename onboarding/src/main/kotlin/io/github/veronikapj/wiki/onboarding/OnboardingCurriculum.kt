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
    val space: String? = null,
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
    @SerialName("confluence-page") CONFLUENCE_PAGE,
    @SerialName("code") CODE,
    @SerialName("github-file") GITHUB_FILE,
}

@Serializable
data class ContentSource(
    val type: SourceType,
    val path: String? = null,
    val query: String? = null,
    val repo: String? = null,  // github-file 전용: 특정 repo 지정 (미지정 시 기본 codeRepo 사용)
    val pageId: String? = null,  // confluence-page 전용: 페이지 ID
    val section: String? = null, // confluence-page 전용: H1 섹션 매칭 키워드
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
