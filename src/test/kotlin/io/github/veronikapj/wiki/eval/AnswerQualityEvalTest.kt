package io.github.veronikapj.wiki.eval

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("eval")
class AnswerQualityEvalTest {

    // --- Format validation helpers ---

    private val markdownPatterns = listOf(
        Regex("^#{1,3} ", RegexOption.MULTILINE),      // # ## ### headings
        Regex("\\*\\*[^*]+\\*\\*"),                     // **bold**
        Regex("\\[([^\\]]+)]\\(https?://[^)]+\\)"),    // [text](url)
    )

    private val slackLinkPattern = Regex("<https?://[^|]+\\|[^>]+>")
    private val stepPattern = Regex("^\\s*(\\d+\\.|•)", RegexOption.MULTILINE)

    private fun hasMarkdownPatterns(text: String): Boolean =
        markdownPatterns.any { it.containsMatchIn(text) }

    private fun hasInlineLinks(text: String): Boolean =
        slackLinkPattern.containsMatchIn(text)

    private fun hasStepStructure(text: String): Boolean =
        stepPattern.findAll(text).count() >= 2

    private fun lineCount(text: String): Int =
        text.trim().lines().filter { it.isNotBlank() }.size

    // --- Tests for validation helpers ---

    @Test
    fun `detects Markdown heading patterns`() {
        assertTrue(hasMarkdownPatterns("# 제목"))
        assertTrue(hasMarkdownPatterns("## 소제목"))
        assertFalse(hasMarkdownPatterns("*제목*"))
        assertFalse(hasMarkdownPatterns("일반 텍스트"))
    }

    @Test
    fun `detects Markdown bold patterns`() {
        assertTrue(hasMarkdownPatterns("이것은 **굵은** 텍스트"))
        assertFalse(hasMarkdownPatterns("이것은 *굵은* 텍스트"))
    }

    @Test
    fun `detects Markdown link patterns`() {
        assertTrue(hasMarkdownPatterns("[배포 가이드](https://wiki.example.com)"))
        assertFalse(hasMarkdownPatterns("<https://wiki.example.com|배포 가이드>"))
    }

    @Test
    fun `detects Slack inline links`() {
        assertTrue(hasInlineLinks("참고: <https://wiki.example.com/page|배포 가이드>"))
        assertFalse(hasInlineLinks("링크 없는 텍스트"))
    }

    @Test
    fun `detects step structure`() {
        val withSteps = """
            1. 먼저 빌드합니다.
            2. 테스트를 실행합니다.
            3. 배포합니다.
        """.trimIndent()
        assertTrue(hasStepStructure(withSteps))

        val withBullets = """
            • 항목 1
            • 항목 2
            • 항목 3
        """.trimIndent()
        assertTrue(hasStepStructure(withBullets))

        assertFalse(hasStepStructure("단순한 텍스트입니다."))
    }

    @Test
    fun `counts non-blank lines`() {
        val text = """
            첫 번째 줄

            두 번째 줄
            세 번째 줄
        """.trimIndent()
        assertTrue(lineCount(text) == 3)
    }

    @Test
    fun `golden dataset has answer quality fields`() {
        val json = this::class.java.getResourceAsStream("/golden-dataset.json")
            ?.reader()?.readText() ?: error("golden-dataset.json not found")
        val cases = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<List<GoldenCase>>(json)

        cases.forEach { case ->
            assertTrue(case.expectedMinLines > 0, "${case.id}: expectedMinLines should be positive")
            assertTrue(case.expectedMaxLines >= case.expectedMinLines, "${case.id}: max should >= min")
            if (case.questionType == QuestionType.ZERO) {
                assertFalse(case.requiresLink, "${case.id}: ZERO type should not require links")
            }
        }
    }
}
