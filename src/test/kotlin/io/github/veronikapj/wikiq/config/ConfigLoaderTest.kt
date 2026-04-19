package io.github.veronikapj.wikiq.config

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
}
