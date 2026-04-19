package io.github.veronikapj.wiki.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE }
enum class EmbeddingMode { LLM_EXPAND, GOOGLE_EMBEDDING }

data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
    val rag: RagConfig = RagConfig(),
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
