package io.github.veronikapj.wiki.config

enum class ModelProvider { ANTHROPIC, GOOGLE, CLAUDE_CODE }

data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
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
