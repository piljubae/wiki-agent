package io.github.veronikapj.wiki.slack

import kotlin.test.Test
import kotlin.test.assertEquals

class MentionParserTest {

    @Test
    fun `strips bot mention from text`() {
        val raw = "<@U12345> 배포 프로세스 알려줘"
        assertEquals("배포 프로세스 알려줘", SlackBotGateway.extractQuery(raw))
    }

    @Test
    fun `strips leading whitespace after mention`() {
        val raw = "<@UBOT>  질문입니다"
        assertEquals("질문입니다", SlackBotGateway.extractQuery(raw))
    }

    @Test
    fun `returns text as-is when no mention`() {
        val raw = "그냥 텍스트"
        assertEquals("그냥 텍스트", SlackBotGateway.extractQuery(raw))
    }
}
