package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

class ProgressAdvisorTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(ProgressAdvisorTool::class.java)

    private fun loadFullJson(): String? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read progress.json: {}", it.message) }
            .getOrNull()
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    @Tool("progressAdvisor")
    @LLMDescription("성과 목표에 대한 조언/피드백/1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘' 질문에 사용하세요.")
    fun advise(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("ProgressAdvisor")

        val json = loadFullJson() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."
        val today = LocalDate.now()

        val advisorPrompt = buildString {
            appendLine("당신은 성과 목표 코칭 전문가입니다. 아래 성과 데이터를 분석하고, 두 관점에서 1:1 피드백을 제공하세요.")
            appendLine()
            appendLine("오늘 날짜: $today")
            appendLine("평가 기간: 2026-01-01 ~ 2026-12-31")
            appendLine()
            appendLine("=== 성과 데이터 (JSON) ===")
            appendLine(json)
            appendLine("=== 끝 ===")
            appendLine()
            appendLine("두 관점으로 피드백을 작성하세요:")
            appendLine()
            appendLine("## 팀장 피드백")
            appendLine("앱개발팀장 관점. 스프린트 리뷰처럼 실행 속도에 집중.")
            appendLine("- 각 지표의 현재 값 vs 목표 값을 보고 실행 속도를 판단하세요.")
            appendLine("- 0이거나 진행이 느린 지표가 있으면 '이거 왜 안 움직여?' 라고 직접적으로 물으세요.")
            appendLine("- 이번 달 또는 이번 주에 할 수 있는 구체적인 액션을 제안하세요.")
            appendLine("- 잘 된 것도 짚어주세요 (completed=true인 지표, 활발한 workstream 등).")
            appendLine("- 톤: 직설적이고 실무적. '이번 주에 이거 해', '이건 잘했네' 느낌.")
            appendLine()
            appendLine("## 부문장 피드백")
            appendLine("클라이언트 부문장 관점. 반기 성과 면담처럼 포트폴리오 전략에 집중.")
            appendLine("- 가중치 배분이 적절한지, 가중치 0인 목표는 괜찮은지 검토하세요.")
            appendLine("- indicator가 비어있는 목표는 '연말에 이걸 어떻게 숫자로 증명할 거야?' 라고 물으세요.")
            appendLine("- g7Expectations가 있으면 각 항목이 실제 활동(events, workstreams)으로 뒷받침되는지 체크하세요.")
            appendLine("- 연말 평가 narrative에서 빠지면 안 되는 포인트를 짚어주세요.")
            appendLine("- 톤: 전략적이고 큰 그림 중심. '하반기 평가에서 이게 빠지면 곤란해' 느낌.")
            appendLine()
            appendLine("형식: Slack mrkdwn으로 출력. *굵게* `코드` 허용. # ## ** [링크](url) 금지.")
        }

        return runBlocking {
            runCatching {
                executor.execute(
                    prompt("advisor") { user(advisorPrompt) }, model
                ).joinToString("") { it.content }.trim()
            }.getOrElse { e ->
                log.error("Advisor LLM call failed: {}", e.message)
                "코칭 피드백 생성 중 오류가 발생했습니다: ${e.message}"
            }
        }
    }
}
