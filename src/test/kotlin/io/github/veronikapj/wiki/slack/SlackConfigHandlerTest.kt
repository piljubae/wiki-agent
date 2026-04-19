package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfluenceConfig
import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.config.ModelProvider
import io.github.veronikapj.wiki.config.SlackConfig
import io.github.veronikapj.wiki.config.WikiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackConfigHandlerTest {

    private fun makeConfig(spaces: List<String> = emptyList()) = WikiConfig(
        model = ModelConfig(ModelProvider.CLAUDE_CODE),
        confluence = ConfluenceConfig("https://co.atlassian.net", "tok", spaces),
        slack = SlackConfig("xoxb-test", "xapp-test"),
    )

    @Test
    fun `handle set spaces updates config`() {
        val handler = SlackConfigHandler(makeConfig())
        val result = handler.handle("/wikiq config space DEV,PM,HR")
        assertEquals(listOf("DEV", "PM", "HR"), handler.currentConfig().confluence.spaces)
        assertTrue(result.contains("DEV"))
    }

    @Test
    fun `handle show returns current spaces`() {
        val handler = SlackConfigHandler(makeConfig(listOf("DEV", "PM")))
        val result = handler.handle("/wikiq config space show")
        assertTrue(result.contains("DEV"))
        assertTrue(result.contains("PM"))
    }

    @Test
    fun `handle unknown command returns help message`() {
        val handler = SlackConfigHandler(makeConfig())
        val result = handler.handle("/wikiq unknown")
        assertTrue(result.contains("사용법") || result.contains("help") || result.contains("config"))
    }
}
