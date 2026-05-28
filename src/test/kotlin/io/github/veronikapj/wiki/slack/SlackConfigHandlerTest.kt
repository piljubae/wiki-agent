package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfluenceConfig
import io.github.veronikapj.wiki.config.ModelConfig
import io.github.veronikapj.wiki.config.ModelProvider
import io.github.veronikapj.wiki.config.SlackConfig
import io.github.veronikapj.wiki.config.WikiConfig
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `memory add stores content`() {
        val memory = ProjectMemory(
            java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
        )
        val handler = SlackConfigHandler(config = makeConfig(), projectMemory = memory)
        val result = handler.handle("/wiki memory add Spring Boot 3.x 기반")
        assertTrue(result.contains("저장 완료"))
        assertTrue(memory.load()!!.contains("Spring Boot 3.x"))
    }

    @Test
    fun `memory show returns content`() {
        val memory = ProjectMemory(
            java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
        )
        memory.add("테스트 항목")
        val handler = SlackConfigHandler(config = makeConfig(), projectMemory = memory)
        val result = handler.handle("/wiki memory show")
        assertTrue(result.contains("테스트 항목"))
    }

    @Test
    fun `memory clear removes content`() {
        val memory = ProjectMemory(
            java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
        )
        memory.add("삭제될 항목")
        val handler = SlackConfigHandler(config = makeConfig(), projectMemory = memory)
        val result = handler.handle("/wiki memory clear")
        assertTrue(result.contains("초기화"))
        assertNull(memory.load())
    }
}
