package io.github.veronikapj.wiki.config

import java.io.File

object ConfigLoader {

    fun load(path: String = ".wiki/config.yml"): WikiConfig =
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
        val githubCodeRepos = mutableListOf<String>()
        var inGithub = false
        var inGithubRepos = false
        var inGithubCodeRepos = false
        var inCodeSearch = false
        var codeSearchBranch = "develop"
        var codeSearchPollIntervalMinutes = 60
        var codeSearchWebhookPort = 0
        var codeSearchLocalRepoPath: String? = null
        var codeSearchEmbeddingMode = EmbeddingMode.LLM_EXPAND
        var codeSearchIndexApiKey: String? = null
        var codeSearchSearchApiKey: String? = null
        var persona = PersonaType.DEFAULT
        var inRouter = false
        var routerProvider: ModelProvider? = null
        var routerModelName: String? = null
        var routerApiKey: String? = null
        var inCallGraph = false
        var callGraphCloneRepoPath = ""
        var callGraphDbPath = "call_graph.db"
        var inPersonalData = false
        var personalDataEnabled = false
        var personalDataProgressFile = ""
        val personalDataAllowedUsers = mutableListOf<String>()
        var inAllowedUsers = false

        for (raw in lines) {
            val line = raw.substringBefore("#").trimEnd()
            val indent = line.length - line.trimStart().length
            when {
                line == "model:" -> { inModel = true; inRouter = false; inConfluence = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "confluence:" -> { inConfluence = true; inRouter = false; inModel = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "slack:" -> { inSlack = true; inRouter = false; inModel = false; inConfluence = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "rag:" -> { inRag = true; inRouter = false; inModel = false; inConfluence = false; inSlack = false; inSpaces = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "github:" -> { inGithub = true; inRouter = false; inModel = false; inConfluence = false; inSlack = false; inRag = false; inSpaces = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "router:" -> { inRouter = true; inCallGraph = false; inModel = false; inConfluence = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "callGraph:" -> { inCallGraph = true; inRouter = false; inModel = false; inConfluence = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false; inPersonalData = false; inAllowedUsers = false }
                line == "personalData:" -> { inPersonalData = true; inAllowedUsers = false; inCallGraph = false; inRouter = false; inModel = false; inConfluence = false; inSlack = false; inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false }
                inConfluence && line.trimStart().startsWith("spaces:") -> inSpaces = true
                inSpaces && line.trimStart().startsWith("- ") -> spaces.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inSpaces && line.isNotBlank() -> inSpaces = false
                inGithub && !inCodeSearch && line.trimStart().startsWith("repos:") && indent == 2 -> { inGithubRepos = true; inGithubCodeRepos = false }
                inGithub && !inCodeSearch && line.trimStart().startsWith("codeRepos:") -> { inGithubCodeRepos = true; inGithubRepos = false }
                inGithub && line.trimStart() == "codeSearch:" -> { inCodeSearch = true; inGithubRepos = false; inGithubCodeRepos = false }
                inGithubRepos && line.trimStart().startsWith("- ") -> githubRepos.add(line.trimStart().removePrefix("- ").trim())
                inGithubCodeRepos && line.trimStart().startsWith("- ") -> githubCodeRepos.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && (inGithubRepos || inGithubCodeRepos) && line.isNotBlank() -> { inGithubRepos = false; inGithubCodeRepos = false }
                inPersonalData && line.trimStart().startsWith("allowedUsers:") -> inAllowedUsers = true
                inAllowedUsers && line.trimStart().startsWith("- ") -> personalDataAllowedUsers.add(line.trimStart().removePrefix("- ").trim())
                !line.trimStart().startsWith("- ") && inAllowedUsers && line.isNotBlank() -> inAllowedUsers = false
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
                inGithub && !inCodeSearch && trimmed.startsWith("enabled:") ->
                    githubEnabled = trimmed.substringAfter("enabled:").trim() == "true"
                inGithub && !inCodeSearch && trimmed.startsWith("token:") ->
                    githubToken = trimmed.substringAfter("token:").trim()
                inCodeSearch && trimmed.startsWith("branch:") ->
                    codeSearchBranch = trimmed.substringAfter("branch:").trim()
                inCodeSearch && trimmed.startsWith("pollIntervalMinutes:") ->
                    codeSearchPollIntervalMinutes = trimmed.substringAfter("pollIntervalMinutes:").trim().toIntOrNull() ?: 60
                inCodeSearch && trimmed.startsWith("webhookPort:") ->
                    codeSearchWebhookPort = trimmed.substringAfter("webhookPort:").trim().toIntOrNull() ?: 0
                inCodeSearch && trimmed.startsWith("localRepoPath:") ->
                    codeSearchLocalRepoPath = trimmed.substringAfter("localRepoPath:").trim().ifEmpty { null }
                inCodeSearch && trimmed.startsWith("embeddingMode:") ->
                    codeSearchEmbeddingMode = runCatching {
                        EmbeddingMode.valueOf(trimmed.substringAfter("embeddingMode:").trim().uppercase())
                    }.getOrDefault(EmbeddingMode.LLM_EXPAND)
                inCodeSearch && trimmed.startsWith("indexApiKey:") ->
                    codeSearchIndexApiKey = trimmed.substringAfter("indexApiKey:").trim().ifEmpty { null }
                inCodeSearch && trimmed.startsWith("searchApiKey:") ->
                    codeSearchSearchApiKey = trimmed.substringAfter("searchApiKey:").trim().ifEmpty { null }
                inRouter && trimmed.startsWith("provider:") ->
                    routerProvider = runCatching {
                        ModelProvider.valueOf(trimmed.substringAfter("provider:").trim().uppercase())
                    }.getOrNull()
                inRouter && trimmed.startsWith("name:") ->
                    routerModelName = trimmed.substringAfter("name:").trim().ifEmpty { null }
                inRouter && trimmed.startsWith("apiKey:") ->
                    routerApiKey = trimmed.substringAfter("apiKey:").trim().ifEmpty { null }
                inPersonalData && !inAllowedUsers && trimmed.startsWith("enabled:") ->
                    personalDataEnabled = trimmed.substringAfter("enabled:").trim() == "true"
                inPersonalData && !inAllowedUsers && trimmed.startsWith("progressFile:") ->
                    personalDataProgressFile = trimmed.substringAfter("progressFile:").trim()
                inCallGraph && trimmed.startsWith("cloneRepoPath:") ->
                    callGraphCloneRepoPath = trimmed.substringAfter("cloneRepoPath:").trim()
                inCallGraph && trimmed.startsWith("dbPath:") ->
                    callGraphDbPath = trimmed.substringAfter("dbPath:").trim()
                indent == 0 && trimmed.startsWith("persona:") ->
                    persona = runCatching {
                        PersonaType.valueOf(trimmed.substringAfter("persona:").trim().uppercase())
                    }.getOrDefault(PersonaType.DEFAULT)
            }
        }

        return WikiConfig(
            model = ModelConfig(provider, modelName, apiKey),
            confluence = ConfluenceConfig(baseUrl, token, spaces),
            slack = SlackConfig(botToken, appToken),
            rag = RagConfig(ragEnabled, chromaUrl, embeddingMode, ragGoogleApiKey),
            github = GithubConfig(
                enabled = githubEnabled,
                token = githubToken,
                repos = githubRepos,
                codeRepos = githubCodeRepos,
                codeSearch = CodeSearchConfig(
                    branch = codeSearchBranch,
                    pollIntervalMinutes = codeSearchPollIntervalMinutes,
                    webhookPort = codeSearchWebhookPort,
                    localRepoPath = codeSearchLocalRepoPath,
                    embeddingMode = codeSearchEmbeddingMode,
                    indexApiKey = codeSearchIndexApiKey,
                    searchApiKey = codeSearchSearchApiKey,
                ),
            ),
            persona = persona,
            routerConfig = routerProvider?.let { ModelConfig(it, routerModelName, routerApiKey) },
            callGraph = if (callGraphCloneRepoPath.isNotBlank())
                CallGraphConfig(callGraphCloneRepoPath, callGraphDbPath) else null,
            personalData = PersonalDataConfig(personalDataEnabled, personalDataProgressFile, personalDataAllowedUsers),
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
            if (config.github.codeRepos.isNotEmpty()) {
                appendLine("  codeRepos:")
                config.github.codeRepos.forEach { appendLine("    - $it") }
            }
            val cs = config.github.codeSearch
            appendLine("  codeSearch:")
            appendLine("    branch: ${cs.branch}")
            appendLine("    pollIntervalMinutes: ${cs.pollIntervalMinutes}")
            if (cs.webhookPort > 0) appendLine("    webhookPort: ${cs.webhookPort}")
            cs.localRepoPath?.let { appendLine("    localRepoPath: $it") }
            appendLine("    embeddingMode: ${cs.embeddingMode}")
            config.routerConfig?.let { router ->
                appendLine("router:")
                appendLine("  provider: ${router.provider}")
                router.name?.let { appendLine("  name: $it") }
                // apiKey is a secret — not written to disk (same pattern as model.apiKey)
            }
        }
        File(path).writeText(yaml)
    }
}
