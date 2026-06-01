package io.github.veronikapj.wiki.agent.tool

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
            model = mockk<LLModel>(),
        )
    }

    @Test fun `advise returns LLM-generated coaching feedback`() {
        val mockResponse = "## 팀장 피드백\n좋은 진행입니다.\n\n## 부문장 피드백\n전략적으로 잘 하고 있어요."
        val tool = createTool(createMockExecutor(mockResponse))
        val result = tool.advise("U_ALLOWED")
        assertTrue(result.contains("팀장"))
        assertTrue(result.contains("부문장"))
        cleanup()
    }

    @Test fun `advise blocks unauthorized user`() {
        val tool = createTool(createMockExecutor("should not reach"))
        val result = tool.advise("U_OTHER")
        assertTrue(result.contains("권한") || result.contains("허용"))
        cleanup()
    }

    @Test fun `advise handles missing file gracefully`() {
        val executor = createMockExecutor("unused")
        val tool = ProgressAdvisorTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
            executor = executor,
            model = mockk<LLModel>(),
        )
        val result = tool.advise("U_ALLOWED")
        assertTrue(result.contains("설정되지 않") || result.contains("파일"))
    }

    private fun cleanup() { File(testFile).delete() }
}
