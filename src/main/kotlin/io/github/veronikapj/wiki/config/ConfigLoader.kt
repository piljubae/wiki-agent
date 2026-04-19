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
        var githubEnabled = false
        var githubToken = ""
        val githubRepos = mutableListOf<String>()
        var inGithub = false
        var inGithubRepos = false

        for (raw in lines) {
            val line = raw.substringBefore("#").trimEnd()
            when {
                line == "model:" -> { inModel = true; inConfluence = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false }
                line == "confluence:" -> { inConfluence = true; inModel = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false }
                line == "slack:" -> { inSlack = true; inModel = false; inConfluence = false; inSpaces = false; inRag = false; inGithub = false }
                line == "rag:" -> { inRag = true; inModel = false; inConfluence = false; inSlack = false; inSpaces = false; inGithub = false }
                line == "github:" -> { inGithub = true; inModel = false; inConfluence = false; inSlack = false; inRag = false; inSpaces = false }
                inConfluence && line.trimStart().startsWith("spaces:") -> inSpaces = true
                inSpaces && line.trimStart().startsWith("- ") -> spaces.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inSpaces && line.isNotBlank() -> inSpaces = false
                inGithub && line.trimStart().startsWith("repos:") -> inGithubRepos = true
                inGithubRepos && line.trimStart().startsWith("- ") -> githubRepos.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inGithubRepos && line.isNotBlank() -> inGithubRepos = false
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
                inGithub && trimmed.startsWith("enabled:") ->
                    githubEnabled = trimmed.substringAfter("enabled:").trim() == "true"
                inGithub && trimmed.startsWith("token:") ->
                    githubToken = trimmed.substringAfter("token:").trim()
            }
        }

        return WikiConfig(
            model = ModelConfig(provider, modelName, apiKey),
            confluence = ConfluenceConfig(baseUrl, token, spaces),
            slack = SlackConfig(botToken, appToken),
            rag = RagConfig(ragEnabled, chromaUrl, embeddingMode, ragGoogleApiKey),
            github = GithubConfig(githubEnabled, githubToken, githubRepos),
        )
    }

    fun save(config: WikiConfig, path: String = ".wikiq/config.yml") {
        val spaces = config.confluence.spaces.joinToString("\n") { "    - $it" }
        val yaml = buildString {
            appendLine("model:")
            appendLine("  provider: ${config.model.provider}")
            config.model.name?.let { appendLine("  name: $it") }
            // apiKey is a secret managed by SecretLoader — not written to disk
            appendLine("confluence:")
            appendLine("  baseUrl: ${config.confluence.baseUrl}")
            // token is a secret managed by SecretLoader — not written to disk
            if (config.confluence.spaces.isNotEmpty()) {
                appendLine("  spaces:")
                appendLine(spaces)
            }
            appendLine("slack:")
            // botToken and appToken are secrets managed by SecretLoader — not written to disk
            appendLine("rag:")
            appendLine("  enabled: ${config.rag.enabled}")
            appendLine("  chromaUrl: ${config.rag.chromaUrl}")
            appendLine("  embeddingMode: ${config.rag.embeddingMode}")
            // googleApiKey is a secret managed by SecretLoader — not written to disk
            appendLine("github:")
            appendLine("  enabled: ${config.github.enabled}")
            if (config.github.repos.isNotEmpty()) {
                appendLine("  repos:")
                config.github.repos.forEach { appendLine("    - $it") }
            }
        }
        File(path).writeText(yaml)
    }
}
