package io.github.veronikapj.wiki.config

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
