package io.github.veronikapj.wiki.search.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.time.LocalDate

class ProgressAdvisorTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(ProgressAdvisorTool::class.java)

    private companion object {
        val URL_PATTERN = Regex("""https?://\S+""")
        val FILE_PATH_PATTERN = Regex("""(?:^|\s)([~/]\S+\.\w+)""")

        const val DATA_REQUEST_MESSAGE =
            "성과 데이터를 찾을 수 없어요. 아래 중 하나를 보내주세요:\n" +
            "• 파일 경로 (예: `/Users/.../progress.json`)\n" +
            "• URL (예: Google Docs, Notion 공유 링크)"
    }

    private fun loadFromConfigFile(): String? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read progress file: {}", it.message) }
            .getOrNull()
    }

    private fun loadFromUrl(url: String): String? {
        return runCatching {
            URI(url).toURL().readText()
        }.onFailure { log.warn("Failed to fetch URL {}: {}", url, it.message) }
            .getOrNull()
    }

    private fun loadFromFilePath(path: String): String? {
        val resolved = if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
        val file = File(resolved)
        if (!file.exists()) return null
        return runCatching { file.readText() }
            .onFailure { log.warn("Failed to read file {}: {}", path, it.message) }
            .getOrNull()
    }

    private fun extractDataFromMessage(message: String): String? {
        URL_PATTERN.find(message)?.value?.let { url ->
            loadFromUrl(url)?.let { return it }
        }
        FILE_PATH_PATTERN.find(message)?.groupValues?.get(1)?.let { path ->
            loadFromFilePath(path)?.let { return it }
        }
        return null
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    private fun isDataRequest(message: String): Boolean {
        val keywords = listOf("피드백", "조언", "1:1", "코칭", "성과", "목표", "진척", "어때")
        return keywords.any { message.contains(it) }
    }

    @Tool("progressAdvisor")
    @LLMDescription("성과 목표에 대한 조언/피드백/1:1 코칭. '조언해줘', '피드백 줘', '1:1 해줘' 질문에 사용하세요.")
    fun advise(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
        @LLMDescription("사용자의 메시지")
        message: String = "",
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("ProgressAdvisor")

        val today = LocalDate.now()

        // 1. config 파일에서 로드
        var data = loadFromConfigFile()

        // 2. 없으면 메시지에서 URL/경로 감지
        if (data == null) {
            data = extractDataFromMessage(message)
        }

        // 3. 데이터 없고 성과 관련 질문이면 링크 요청
        if (data == null && isDataRequest(message)) {
            return DATA_REQUEST_MESSAGE
        }

        // 4. LLM 프롬프트 구성
        val slackFormatRule = """
            |[출력 형식: Slack mrkdwn — 이 규칙을 최우선으로 준수]
            |허용: *굵게* _기울임_ ~취소선~ `코드` :emoji: • 불릿 1. 번호
            |금지: # ## ### **굵게** --- |테이블| [링크](url)
            |굵게는 *한 개*로 감싼다. 표는 • 불릿으로 대체한다.
        """.trimMargin()

        val advisorPrompt = buildString {
            appendLine(slackFormatRule)
            appendLine()
            if (data != null) {
                appendLine("당신은 성과 목표 코칭 전문가입니다. 아래 성과 데이터를 분석하고, 두 관점에서 1:1 피드백을 제공하세요.")
                appendLine()
                appendLine("오늘 날짜: $today")
                appendLine("평가 기간: 2026-01-01 ~ 2026-12-31")
                appendLine()
                appendLine("=== 성과 데이터 ===")
                appendLine(data)
                appendLine("=== 끝 ===")
                appendLine()
                appendLine("두 관점으로 피드백을 작성하세요:")
                appendLine()
                appendLine("*팀장 피드백*")
                appendLine("앱개발팀장 관점. 스프린트 리뷰처럼 실행 속도에 집중.")
                appendLine("- 각 지표의 현재 값 vs 목표 값을 보고 실행 속도를 판단하세요.")
                appendLine("- 0이거나 진행이 느린 지표가 있으면 '이거 왜 안 움직여?' 라고 직접적으로 물으세요.")
                appendLine("- 이번 달 또는 이번 주에 할 수 있는 구체적인 액션을 제안하세요.")
                appendLine("- 잘 된 것도 짚어주세요 (completed=true인 지표, 활발한 workstream 등).")
                appendLine("- 톤: 직설적이고 실무적. '이번 주에 이거 해', '이건 잘했네' 느낌.")
                appendLine()
                appendLine("*부문장 피드백*")
                appendLine("클라이언트 부문장 관점. 반기 성과 면담처럼 포트폴리오 전략에 집중.")
                appendLine("- 가중치 배분이 적절한지, 가중치 0인 목표는 괜찮은지 검토하세요.")
                appendLine("- indicator가 비어있는 목표는 '연말에 이걸 어떻게 숫자로 증명할 거야?' 라고 물으세요.")
                appendLine("- g7Expectations가 있으면 각 항목이 실제 활동(events, workstreams)으로 뒷받침되는지 체크하세요.")
                appendLine("- 연말 평가 narrative에서 빠지면 안 되는 포인트를 짚어주세요.")
                appendLine("- 톤: 전략적이고 큰 그림 중심. '하반기 평가에서 이게 빠지면 곤란해' 느낌.")
            } else {
                appendLine("당신은 성과·커리어 코칭 전문가입니다. 1:1 코칭 대화를 이어가세요.")
                appendLine("성과 데이터 없이 대화하고 있습니다. 사용자의 고민에 공감하고 구체적인 조언을 하세요.")
                appendLine("톤: 따뜻하지만 직설적. 실행 가능한 제안 위주.")
            }
            appendLine()
            appendLine("사용자 메시지: $message")
        }

        val params = if (model.provider == AnthropicModels.Haiku_4_5.provider) {
            AnthropicParams(maxTokens = 4096)
        } else {
            LLMParams(maxTokens = 4096)
        }

        return runBlocking {
            runCatching {
                executor.execute(
                    prompt("advisor", params = params) { user(advisorPrompt) }, model
                ).joinToString("") { it.content }.trim()
            }.getOrElse { e ->
                log.error("Advisor LLM call failed: {}", e.message)
                "코칭 피드백 생성 중 오류가 발생했습니다: ${e.message}"
            }
        }
    }
}
