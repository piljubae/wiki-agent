package io.github.veronikapj.wiki.config

import java.io.File

object ConfigLoader {

    fun load(path: String = ".wikiq/config.yml"): WikiConfig =
        fromString(File(path).readText())

    fun fromString(yaml: String): WikiConfig {
        val lines = yaml.lines()
        var provider = ModelProvider.CLAUDE_CODE
        var modelName: String? = null
        var apiKey: String? = null
        var baseUrl = ""
        var token = ""
        val spaces = mutableListOf<String>()
        var botToken = ""
        var appToken = ""
        var inModel = false
        var inConfluence = false
        var inSpaces = false
        var inSlack = false
        var ragEnabled = false
        var chromaUrl = "http://localhost:8000"
        var embeddingMode = EmbeddingMode.LLM_EXPAND
        var ragGoogleApiKey: String? = null
        var inRag = false

        for (raw in lines) {
            val line = raw.substringBefore("#").trimEnd()
            when {
                line == "model:" -> { inModel = true; inConfluence = false; inSlack = false; inSpaces = false; inRag = false }
                line == "confluence:" -> { inConfluence = true; inModel = false; inSlack = false; inSpaces = false; inRag = false }
                line == "slack:" -> { inSlack = true; inModel = false; inConfluence = false; inSpaces = false; inRag = false }
                line == "rag:" -> { inRag = true; inModel = false; inConfluence = false; inSlack = false; inSpaces = false }
                inConfluence && line.trimStart().startsWith("spaces:") -> inSpaces = true
                inSpaces && line.trimStart().startsWith("- ") -> spaces.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inSpaces && line.isNotBlank() -> inSpaces = false
            }
            val trimmed = line.trim()
            when {
                inModel && trimmed.startsWith("provider:") ->
                    provider = runCatching {
                        ModelProvider.valueOf(trimmed.substringAfter("provider:").trim().uppercase())
                    }.getOrDefault(ModelProvider.CLAUDE_CODE)
                inModel && trimmed.startsWith("name:") ->
                    modelName = trimmed.substringAfter("name:").trim().ifEmpty { null }
                inModel && trimmed.startsWith("apiKey:") ->
                    apiKey = trimmed.substringAfter("apiKey:").trim().ifEmpty { null }
                inConfluence && trimmed.startsWith("baseUrl:") ->
                    baseUrl = trimmed.substringAfter("baseUrl:").trim()
                inConfluence && trimmed.startsWith("token:") ->
                    token = trimmed.substringAfter("token:").trim()
                inSlack && trimmed.startsWith("botToken:") ->
                    botToken = trimmed.substringAfter("botToken:").trim()
                inSlack && trimmed.startsWith("appToken:") ->
                    appToken = trimmed.substringAfter("appToken:").trim()
                inRag && trimmed.startsWith("enabled:") ->
                    ragEnabled = trimmed.substringAfter("enabled:").trim() == "true"
                inRag && trimmed.startsWith("chromaUrl:") ->
                    chromaUrl = trimmed.substringAfter("chromaUrl:").trim()
                inRag && trimmed.startsWith("embeddingMode:") ->
                    embeddingMode = runCatching {
                        EmbeddingMode.valueOf(trimmed.substringAfter("embeddingMode:").trim().uppercase())
                    }.getOrDefault(EmbeddingMode.LLM_EXPAND)
                inRag && trimmed.startsWith("googleApiKey:") ->
                    ragGoogleApiKey = trimmed.substringAfter("googleApiKey:").trim().ifEmpty { null }
            }
        }

        return WikiConfig(
            model = ModelConfig(provider, modelName, apiKey),
            confluence = ConfluenceConfig(baseUrl, token, spaces),
            slack = SlackConfig(botToken, appToken),
            rag = RagConfig(ragEnabled, chromaUrl, embeddingMode, ragGoogleApiKey),
        )
    }

    fun save(config: WikiConfig, path: String = ".wikiq/config.yml") {
        val spaces = config.confluence.spaces.joinToString("\n") { "    - $it" }
        val yaml = buildString {
            appendLine("model:")
            appendLine("  provider: ${config.model.provider}")
            config.model.name?.let { appendLine("  name: $it") }
            config.model.apiKey?.let { appendLine("  apiKey: $it") }
            appendLine("confluence:")
            appendLine("  baseUrl: ${config.confluence.baseUrl}")
            appendLine("  token: ${config.confluence.token}")
            if (config.confluence.spaces.isNotEmpty()) {
                appendLine("  spaces:")
                appendLine(spaces)
            }
            appendLine("slack:")
            appendLine("  botToken: ${config.slack.botToken}")
            appendLine("  appToken: ${config.slack.appToken}")
        }
        File(path).writeText(yaml)
    }
}
