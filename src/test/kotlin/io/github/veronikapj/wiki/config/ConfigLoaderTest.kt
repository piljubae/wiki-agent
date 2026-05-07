package io.github.veronikapj.wiki.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigLoaderTest {

    @Test
    fun `loads CLAUDE_CODE provider`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: mytoken
              spaces:
                - DEV
                - PM
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.CLAUDE_CODE, config.model.provider)
        assertEquals(listOf("DEV", "PM"), config.confluence.spaces)
    }

    @Test
    fun `loads ANTHROPIC provider with model name`() {
        val yaml = """
            model:
              provider: ANTHROPIC
              name: claude-sonnet-4-6
              apiKey: sk-ant-test
            confluence:
              baseUrl: https://example.atlassian.net
              token: token
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.ANTHROPIC, config.model.provider)
        assertEquals("claude-sonnet-4-6", config.model.name)
    }

    @Test
    fun `inline comment stripped from value`() {
        val yaml = """
            model:
              provider: GOOGLE # gemini
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.GOOGLE, config.model.provider)
    }

    @Test
    fun `loads rag config`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
            rag:
              enabled: true
              chromaUrl: http://localhost:8000
              embeddingMode: GOOGLE_EMBEDDING
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(true, config.rag.enabled)
        assertEquals("http://localhost:8000", config.rag.chromaUrl)
        assertEquals(EmbeddingMode.GOOGLE_EMBEDDING, config.rag.embeddingMode)
    }

    @Test
    fun `rag defaults to disabled`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(false, config.rag.enabled)
        assertEquals(EmbeddingMode.LLM_EXPAND, config.rag.embeddingMode)
    }

    @Test
    fun `loads github config`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
            github:
              enabled: true
              repos:
                - owner/repo1
                - owner/repo2
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(true, config.github.enabled)
        assertEquals(listOf("owner/repo1", "owner/repo2"), config.github.repos)
    }

    @Test
    fun `save and reload preserves rag config`() {
        val config = WikiConfig(
            model = ModelConfig(provider = ModelProvider.CLAUDE_CODE),
            confluence = ConfluenceConfig(baseUrl = "https://x.atlassian.net", token = "tok"),
            slack = SlackConfig(),
            rag = RagConfig(enabled = true, chromaUrl = "http://chroma:8000", embeddingMode = EmbeddingMode.GOOGLE_EMBEDDING),
        )
        val tmpFile = java.io.File.createTempFile("config-test", ".yml")
        try {
            ConfigLoader.save(config, tmpFile.absolutePath)
            val reloaded = ConfigLoader.load(tmpFile.absolutePath)
            assertEquals(true, reloaded.rag.enabled)
            assertEquals("http://chroma:8000", reloaded.rag.chromaUrl)
            assertEquals(EmbeddingMode.GOOGLE_EMBEDDING, reloaded.rag.embeddingMode)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `router section absent means routerConfig is null`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertNull(config.routerConfig)
    }

    @Test
    fun `router section with GOOGLE provider is parsed`() {
        val yaml = """
            model:
              provider: CLAUDE_CODE
            router:
              provider: GOOGLE
              apiKey: gkey
            confluence:
              baseUrl: https://example.atlassian.net
              token: tok
        """.trimIndent()
        val config = ConfigLoader.fromString(yaml)
        assertEquals(ModelProvider.GOOGLE, config.routerConfig?.provider)
        assertEquals("gkey", config.routerConfig?.apiKey)
    }

    @Test
    fun `save and reload preserves routerConfig provider`() {
        val config = WikiConfig(
            model = ModelConfig(provider = ModelProvider.CLAUDE_CODE),
            confluence = ConfluenceConfig(baseUrl = "https://x.atlassian.net", token = "tok"),
            routerConfig = ModelConfig(provider = ModelProvider.GOOGLE),
        )
        val tmpFile = java.io.File.createTempFile("config-router-test", ".yml")
        try {
            ConfigLoader.save(config, tmpFile.absolutePath)
            val reloaded = ConfigLoader.load(tmpFile.absolutePath)
            assertEquals(ModelProvider.GOOGLE, reloaded.routerConfig?.provider)
        } finally {
            tmpFile.delete()
        }
    }
}
