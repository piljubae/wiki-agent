package io.github.veronikapj.wiki.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE, GEMINI_CODE }
enum class EmbeddingMode { LLM_EXPAND, GOOGLE_EMBEDDING }

data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
    val rag: RagConfig = RagConfig(),
    val github: GithubConfig = GithubConfig(),
)

data class ModelConfig(
    val provider: ModelProvider = ModelProvider.CLAUDE_CODE,
    val name: String? = null,
    val apiKey: String? = null,
)

data class ConfluenceConfig(
    val baseUrl: String = "",
    val token: String = "",
    val spaces: List<String> = emptyList(),
)

data class SlackConfig(
    val botToken: String = "",
    val appToken: String = "",
)

data class RagConfig(
    val enabled: Boolean = false,
    val chromaUrl: String = "http://localhost:8000",
    val embeddingMode: EmbeddingMode = EmbeddingMode.LLM_EXPAND,
    val googleApiKey: String? = null,
)

data class GithubConfig(
    val enabled: Boolean = false,
    val token: String = "",
    val repos: List<String> = emptyList(),
    val codeRepos: List<String> = emptyList(),
    val codeSearch: CodeSearchConfig = CodeSearchConfig(),
)

data class CodeSearchConfig(
    val branch: String = "develop",
    val pollIntervalMinutes: Int = 60,
    val webhookPort: Int = 0,
    val localRepoPath: String? = null,  // 설정 시 GitHub API 대신 로컬 체크아웃에서 파일 읽기
)
