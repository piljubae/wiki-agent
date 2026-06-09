package io.github.veronikapj.wiki.search.tool

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ProgressAdvisorToolTest {

    private val testFile = "build/test-advisor-progress-${System.nanoTime()}.json"
    private val sampleJson = """
    {
      "version": "1.0",
      "year": 2026,
      "owner": { "name": "테스트유저", "team": "테스트팀" },
      "goals": [
        {
          "id": "goal-1",
          "name": "기술력 향상",
          "weight": 30,
          "keywords": ["Tech"],
          "indicators": [
            { "id": "ind-1", "name": "발표", "start": 0, "target": 2, "current": 0, "unit": "회" }
          ]
        },
        {
          "id": "goal-2",
          "name": "자동화 구축",
          "weight": 40,
          "keywords": ["AI"],
          "indicators": [
            { "id": "ind-2", "name": "파이프라인", "start": 0, "target": 3, "current": 1, "unit": "건" }
          ]
        }
      ],
      "g7Expectations": [
        { "id": "g7-1", "label": "대규모 과제", "linkedGoals": ["goal-1"], "status": "covered" }
      ]
    }
    """.trimIndent()

    private fun createMockExecutor(response: String): MultiLLMPromptExecutor {
        val executor = mockk<MultiLLMPromptExecutor>()
        coEvery { executor.execute(any(), any()) } returns listOf(
            Message.Assistant(content = response, metaInfo = ResponseMetaInfo.Empty)
        )
        return executor
    }

    private fun createTool(executor: MultiLLMPromptExecutor): ProgressAdvisorTool {
        File(testFile).writeText(sampleJson)
        return ProgressAdvisorTool(
            progressFile = testFile,
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(relaxed = true),
        )
    }

    private fun createToolNoFile(executor: MultiLLMPromptExecutor): ProgressAdvisorTool {
        return ProgressAdvisorTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(relaxed = true),
        )
    }

    @Test fun `advise with data returns coaching feedback`() {
        val mockResponse = "## 팀장 피드백\n좋은 진행입니다.\n\n## 부문장 피드백\n전략적으로 잘 하고 있어요."
        val tool = createTool(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED", "피드백 줘")
        assertTrue(result.contains("팀장"))
        assertTrue(result.contains("부문장"))
        cleanup()
    }

    @Test fun `advise blocks unauthorized user`() {
        val tool = createTool(createMockExecutor("should not reach"))
        val result = tool.advise("U_OTHER", "피드백 줘")
        assertTrue(result.contains("권한") || result.contains("허용"))
        cleanup()
    }

    @Test fun `advise without data asks for link or path`() {
        val tool = createToolNoFile(createMockExecutor("unused"))
        val result = tool.advise("U_ALLOWED", "피드백 줘")
        assertTrue(result.contains("파일 경로") || result.contains("URL"))
    }

    @Test fun `advise reads local file path from message`() {
        val tempFile = File.createTempFile("progress-test", ".json")
        tempFile.writeText(sampleJson)
        try {
            val mockResponse = "코칭 결과입니다."
            val tool = createToolNoFile(createMockExecutor(mockResponse))
            val result = tool.advise("U_ALLOWED", "여기 봐줘 ${tempFile.absolutePath}")
            assertTrue(result.contains("코칭"))
        } finally {
            tempFile.delete()
        }
    }

    @Test fun `advise without data allows freeform coaching`() {
        val mockResponse = "커리어 조언입니다."
        val tool = createToolNoFile(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED", "요즘 역할이 애매한데 어떻게 해야 할까")
        assertTrue(result.contains("커리어"))
    }

    private fun cleanup() { File(testFile).delete() }
}
