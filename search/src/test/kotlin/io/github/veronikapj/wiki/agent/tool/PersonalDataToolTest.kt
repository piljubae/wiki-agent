package io.github.veronikapj.wiki.agent.tool

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalDataToolTest {

    private val testFile = "build/test-progress-${System.nanoTime()}.json"
    private val sampleJson = """
    {
      "version": "1.0",
      "year": 2026,
      "owner": { "name": "배필주", "team": "프로덕트앱개발" },
      "goals": [
        {
          "id": "goal-1",
          "name": "Google 협업을 통한 Android 기술력 향상",
          "weight": 20,
          "keywords": ["Google", "I/O"],
          "indicators": [
            { "id": "io-workshop", "name": "I/O 워크숍 발표", "start": 0, "target": 1, "current": 0, "unit": "회" }
          ]
        },
        {
          "id": "goal-2",
          "name": "AI 팀 셋업 자동화",
          "weight": 30,
          "keywords": ["AI", "Skill"],
          "indicators": [
            { "id": "team-skills", "name": "팀 공용 Skill/MCP 자산", "start": 3, "target": 8, "current": 5, "unit": "개" },
            { "id": "code-review-bot", "name": "AI 코드리뷰 봇", "start": 0, "target": 1, "current": 1, "unit": "건", "completed": true }
          ]
        }
      ]
    }
    """.trimIndent()

    @Test fun `getProgressSummary returns all goals with progress`() {
        val tool = createTool()
        val result = tool.getProgressSummary("U_ALLOWED")
        assertTrue(result.contains("Google"))
        assertTrue(result.contains("AI"))
        assertTrue(result.contains("5"))
        assertTrue(result.contains("8"))
        cleanup()
    }

    @Test fun `getProgressSummary blocks unauthorized user`() {
        val tool = createTool()
        val result = tool.getProgressSummary("U_OTHER")
        assertTrue(result.contains("권한") || result.contains("허용"))
        cleanup()
    }

    @Test fun `queryGoal finds by keyword`() {
        val tool = createTool()
        val result = tool.queryGoal("AI", "U_ALLOWED")
        assertTrue(result.contains("AI"))
        assertTrue(result.contains("Skill"))
        assertFalse(result.contains("Google"))
        cleanup()
    }

    @Test fun `queryGoal returns not-found for unmatched keyword`() {
        val tool = createTool()
        val result = tool.queryGoal("없는키워드", "U_ALLOWED")
        assertTrue(result.contains("찾을 수 없") || result.contains("없습니다"))
        cleanup()
    }

    @Test fun `handles missing file gracefully`() {
        val tool = PersonalDataTool(
            progressFile = "/nonexistent/progress.json",
            allowedUsers = setOf("U_ALLOWED"),
        )
        val result = tool.getProgressSummary("U_ALLOWED")
        assertTrue(result.contains("설정되지 않") || result.contains("파일"))
    }

    private fun createTool(userId: String = "U_ALLOWED"): PersonalDataTool {
        File(testFile).writeText(sampleJson)
        return PersonalDataTool(
            progressFile = testFile,
            allowedUsers = setOf("U_ALLOWED"),
        )
    }

    private fun cleanup() { File(testFile).delete() }
}
