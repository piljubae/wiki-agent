package io.github.veronikapj.wiki.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SecretLoaderTest {

    @Test
    fun `resolve returns config value when no env or dotenv`() {
        val result = SecretLoader.resolve("NONEXISTENT_KEY_XYZ_12345", "fallback-value")
        assertEquals("fallback-value", result)
    }

    @Test
    fun `parseDotEnv parses KEY=VALUE lines`() {
        val content = """
            SLACK_BOT_TOKEN=xoxb-test
            # comment line
            CONFLUENCE_TOKEN=mytoken
            EMPTY_LINE=
        """.trimIndent()
        val map = SecretLoader.parseDotEnv(content)
        assertEquals("xoxb-test", map["SLACK_BOT_TOKEN"])
        assertEquals("mytoken", map["CONFLUENCE_TOKEN"])
        assertEquals(null, map["# comment line"])
    }

    @Test
    fun `parseDotEnv ignores comments and blank lines`() {
        val content = "# this is a comment\n\nKEY=value"
        val map = SecretLoader.parseDotEnv(content)
        assertEquals(1, map.size)
        assertEquals("value", map["KEY"])
    }

    @Test
    fun `parseDotEnv keeps equals signs inside value`() {
        // base64/패딩처럼 값에 '='가 포함돼도 첫 '='만 구분자로 사용
        val map = SecretLoader.parseDotEnv("TOKEN=ab=cd==")
        assertEquals("ab=cd==", map["TOKEN"])
    }

    @Test
    fun `parseDotEnv skips blank values`() {
        val map = SecretLoader.parseDotEnv("EMPTY=\nFILLED=x")
        assertEquals(null, map["EMPTY"])
        assertEquals("x", map["FILLED"])
    }
}
