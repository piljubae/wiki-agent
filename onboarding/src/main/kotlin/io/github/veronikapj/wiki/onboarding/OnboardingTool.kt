package io.github.veronikapj.wiki.onboarding

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.github.veronikapj.wiki.search.tool.CodeSearchTool
import io.github.veronikapj.wiki.search.tool.ConfluenceTool
import io.github.veronikapj.wiki.search.tool.SourceTracker
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.github.GitHubCodeClient
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class OnboardingTool(
    private val curriculumPath: String = ".wiki/onboarding/curriculum.yaml",
    private val projectName: String = "kurly-android",
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val confluenceTool: ConfluenceTool,
    private val confluenceClient: ConfluenceClient? = null,
    private val codeSearchTool: CodeSearchTool,
    private val codeClient: GitHubCodeClient? = null,
    private val codeRepo: String? = null,
    private val codeBranch: String = "develop",
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(OnboardingTool::class.java)

    private val curriculum: OnboardingCurriculum? by lazy {
        CurriculumLoader.load(curriculumPath)
    }

    /** 위키 페이지 ID (curriculum.yaml의 첫 confluence-page source에서 추출) */
    private val wikiPageId: String? by lazy {
        curriculum?.phases?.flatMap { it.sources }
            ?.firstOrNull { it.type == SourceType.CONFLUENCE_PAGE }?.pageId
    }

    /** 온보딩 SSOT 스페이스 (curriculum.yaml의 최상위 space 필드). 빈 문자열은 미설정으로 취급. */
    private val onboardingSpace: String? by lazy { curriculum?.space?.takeIf { it.isNotBlank() } }

    private val gatherer: ContentGatherer by lazy {
        ContentGatherer(
            confluenceClient = confluenceClient,
            confluenceTool = confluenceTool,
            codeSearchTool = codeSearchTool,
            codeClient = codeClient,
            codeRepo = codeRepo,
            codeBranch = codeBranch,
            wikiPageId = wikiPageId,
            onboardingSpace = onboardingSpace,
        )
    }

    // ── Intent classification ──

    private enum class Intent {
        START, LEVEL_RESPONSE, NEXT, SKIP, PROGRESS, JUMP, QUESTION
    }

    private fun classifyIntent(message: String): Intent {
        val trimmed = message.trim()

        // LEVEL_RESPONSE: comma or space separated A/B/C pattern (e.g. "B, A, A" or "B A A")
        if (LEVEL_PATTERN.matches(trimmed)) return Intent.LEVEL_RESPONSE

        // NEXT: exact matches
        if (trimmed in NEXT_KEYWORDS) return Intent.NEXT

        // SKIP: exact matches
        if (trimmed in SKIP_KEYWORDS) return Intent.SKIP

        // START: contains "온보딩" + ("시작" or "이어")
        if (trimmed.contains("온보딩") && (trimmed.contains("시작") || trimmed.contains("이어"))) {
            return Intent.START
        }

        // PROGRESS: contains keywords
        if (trimmed.contains("진행률") || trimmed.contains("현황") || trimmed.contains("progress")) {
            return Intent.PROGRESS
        }

        // JUMP: 숫자만 (e.g. "5", "12") 또는 "N번" 또는 "다시 보여/알려" + step 이름
        if (JUMP_NUMBER_PATTERN.matches(trimmed)) return Intent.JUMP
        if (trimmed.contains("다시 보여") || trimmed.contains("다시 알려") || trimmed.contains("보여줘")) {
            return Intent.JUMP
        }

        return Intent.QUESTION
    }

    // ── Main entry point ──

    fun handle(userId: String, message: String, conversationContext: String = ""): String {
        tracker?.record("Onboarding")
        val intent = classifyIntent(message)
        log.info("Onboarding intent for user={}: {} (message={})", userId, intent, message.take(50))

        return when (intent) {
            Intent.START -> handleStart(userId)
            Intent.LEVEL_RESPONSE -> handleLevelResponse(userId, message)
            Intent.NEXT -> handleNext(userId)
            Intent.SKIP -> handleSkip(userId)
            Intent.PROGRESS -> handleProgress(userId)
            Intent.JUMP -> handleJump(userId, message)
            Intent.QUESTION -> handleQuestion(userId, message, conversationContext)
        }
    }

    // ── Intent handlers ──

    private fun handleStart(userId: String): String {
        val session = OnboardingSessionStore.load(userId)
        if (session != null) {
            if (session.currentStepId != null) {
                val step = findCurrentStep(session) ?: return "커리큘럼이 변경되어 현재 단계를 찾을 수 없습니다. 다음 단계로 이동합니다."
                return generateGuide(userId, step, session)
            }
            // 이미 완료된 세션
            return ":white_check_mark: 이전 온보딩을 이미 완료했습니다!\n\n" +
                "다시 처음부터 시작하려면 \"온보딩 초기화\"라고 입력해주세요.\n" +
                "특정 단계를 다시 보려면 \"OO 다시 보여줘\"라고 말해주세요."
        }
        return LEVEL_CHECK_MESSAGE
    }

    private fun handleLevelResponse(userId: String, message: String): String {
        val cur = curriculum ?: return "커리큘럼 파일을 불러올 수 없습니다. ($curriculumPath)"

        val level = parseLevelResponse(message.trim())
            ?: return "레벨을 파싱할 수 없습니다. 예시: `B, A, A` (Android, Compose, 도메인 순서)"

        val steps = buildStepsForLevel(level)
        if (steps.isEmpty()) return "커리큘럼에 단계가 없습니다."

        val session = OnboardingSessionStore.create(userId, level, steps)
        val firstStep = cur.phases.firstOrNull { it.id == session.currentStepId }
            ?: return "첫 번째 단계를 찾을 수 없습니다."

        return generateGuide(userId, firstStep, session)
    }

    private fun handleNext(userId: String): String {
        val session = OnboardingSessionStore.advanceStep(userId)
            ?: return "진행 중인 온보딩 세션이 없습니다. `온보딩 시작`으로 시작해 주세요."

        if (session.currentStepId == null) {
            return COMPLETION_MESSAGE
        }

        val step = findCurrentStep(session)
            ?: return "커리큘럼이 변경되어 다음 단계를 찾을 수 없습니다."
        return generateGuide(userId, step, session)
    }

    private fun handleSkip(userId: String): String {
        val session = OnboardingSessionStore.skipStep(userId)
            ?: return "진행 중인 온보딩 세션이 없습니다. `온보딩 시작`으로 시작해 주세요."

        if (session.currentStepId == null) {
            return COMPLETION_MESSAGE
        }

        val step = findCurrentStep(session)
            ?: return "커리큘럼이 변경되어 다음 단계를 찾을 수 없습니다."
        return generateGuide(userId, step, session)
    }

    private fun handleProgress(userId: String): String {
        val session = OnboardingSessionStore.load(userId)
            ?: return "진행 중인 온보딩 세션이 없습니다. `온보딩 시작`으로 시작해 주세요."
        return formatProgress(session)
    }

    private fun handleJump(userId: String, message: String): String {
        val cur = curriculum ?: return "커리큘럼 파일을 불러올 수 없습니다."
        val session = OnboardingSessionStore.load(userId)
            ?: return "진행 중인 온보딩 세션이 없습니다. `온보딩 시작`으로 시작해 주세요."

        val trimmed = message.trim()

        // 1) 숫자로 점프 (e.g. "5", "5번")
        val number = JUMP_NUMBER_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) {
            val targetStep = session.steps.getOrNull(number - 1)
                ?: return "잘못된 번호입니다. 1~${session.steps.size} 범위로 입력해주세요."
            val step = cur.phases.firstOrNull { it.id == targetStep.id }
                ?: return "해당 단계를 찾을 수 없습니다."
            // currentStepId 업데이트
            OnboardingSessionStore.jumpToStep(userId, targetStep.id)
            val updated = OnboardingSessionStore.load(userId) ?: session
            return generateGuide(userId, step, updated)
        }

        // 2) 이름으로 점프 (e.g. "도메인 용어 다시 보여줘", "Compose 컨벤션 보여줘")
        val step = cur.phases.firstOrNull { step ->
            trimmed.contains(step.name, ignoreCase = true)
        }
        if (step != null) {
            OnboardingSessionStore.jumpToStep(userId, step.id)
            val updated = OnboardingSessionStore.load(userId) ?: session
            return generateGuide(userId, step, updated)
        }

        return "해당 단계를 찾을 수 없습니다. 번호(예: `5`) 또는 단계 이름을 입력해 주세요.\n`진행률`을 입력하면 전체 목록을 볼 수 있습니다."
    }

    private fun handleQuestion(userId: String, message: String, conversationContext: String): String {
        val session = OnboardingSessionStore.load(userId)
        val cur = curriculum
        val currentStep = if (session?.currentStepId != null && cur != null) {
            cur.phases.firstOrNull { it.id == session.currentStepId }
        } else null

        val gathered = gatherer.gatherForQuestion(message, currentStep)
        val contentBlock = ContentGatherer.formatBlocks(gathered)

        val contextBlock = buildString {
            if (currentStep != null) {
                appendLine("현재 온보딩 단계: ${currentStep.name} (Phase ${currentStep.phase}, ${currentStep.day})")
            }
            if (contentBlock.isNotBlank()) {
                appendLine()
                appendLine("=== 질문 관련 자료 (출처별) ===")
                appendLine(contentBlock)
                appendLine("=== 끝 ===")
            }
            if (conversationContext.isNotBlank()) {
                appendLine()
                appendLine("=== 대화 히스토리 ===")
                appendLine(conversationContext)
                appendLine("=== 끝 ===")
            }
        }

        val questionPrompt = buildString {
            appendLine(SLACK_FORMAT_RULE)
            appendLine()
            appendLine("당신은 컬리(Kurly) $projectName 프로젝트의 신규 입사자 온보딩을 도와주는 멘토입니다.")
            appendLine("컬리는 한국의 신선식품 이커머스 플랫폼이며, 프로젝트명은 '$projectName'입니다.")
            appendLine("온보딩 대상은 $projectName (Android 앱) 코드베이스입니다. 이 온보딩 도구 자체(wiki-agent)의 구조나 파일을 설명하지 마세요.")
            appendLine("아래 자료를 바탕으로 질문에 친절하고 정확하게 답변하세요. 자료에 없는 파일 경로·클래스명은 추측하지 마세요.")
            appendLine("모르는 내용은 모른다고 하고, 관련 문서나 담당자를 안내하세요.")
            appendLine(IOS_REFERENCE_RULE)
            if (contextBlock.isNotBlank()) {
                appendLine()
                appendLine(contextBlock)
            }
            appendLine()
            appendLine("사용자 질문: $message")
        }

        val answer = callLLM(questionPrompt)
        if (!answer.startsWith("가이드 생성 중 오류")) {
            OnboardingSessionStore.addMemo(userId, "질문: ${message.take(80)}")
        }
        return answer
    }

    // ── Helper methods ──

    private fun buildStepsForLevel(level: UserLevel): List<StepStatus> {
        val cur = curriculum ?: return emptyList()

        return cur.phases.map { step ->
            val shouldSkip = step.skippable && step.levelFilter != null &&
                step.levelFilter.skipWhen.all { (dimension, requiredLevel) ->
                    when (dimension) {
                        "android" -> level.android == requiredLevel
                        "compose" -> level.compose == requiredLevel
                        "domain" -> level.domain == requiredLevel
                        else -> false
                    }
                }

            StepStatus(
                id = step.id,
                name = step.name,
                phase = step.phase,
                status = if (shouldSkip) StepStatusType.SKIPPED else StepStatusType.PENDING,
            )
        }
    }

    private fun findCurrentStep(session: OnboardingSession): CurriculumStep? {
        val cur = curriculum ?: return null
        val currentId = session.currentStepId ?: return null

        val step = cur.phases.firstOrNull { it.id == currentId }
        if (step != null) return step

        // Curriculum was updated — advance to the next existing step
        log.warn("Step {} not found in curriculum, advancing to next", currentId)
        val updated = OnboardingSessionStore.advanceStep(session.userId) ?: return null
        val nextId = updated.currentStepId ?: return null
        return cur.phases.firstOrNull { it.id == nextId }
    }

    private fun generateGuide(userId: String, step: CurriculumStep, session: OnboardingSession): String {
        val gathered = gatherer.gather(step)
        val contentBlock = ContentGatherer.formatBlocks(gathered)

        val phaseSteps = session.steps.filter { it.phase == step.phase }
        val stepIndex = phaseSteps.indexOfFirst { it.id == step.id } + 1
        val phaseTotal = phaseSteps.size
        val header = ":books: *[Phase ${step.phase}: $stepIndex/$phaseTotal] ${step.name}* (${step.day})"

        log.info("Generating guide for step={}, sourceCount={}", step.id, gathered.size)

        val guidePrompt = buildString {
            appendLine(SLACK_FORMAT_RULE)
            appendLine()
            appendLine("당신은 컬리(Kurly) $projectName 프로젝트의 신규 입사자 온보딩 멘토입니다.")
            appendLine("컬리는 한국의 신선식품 이커머스 플랫폼이며, 프로젝트명은 '$projectName'입니다.")
            appendLine("온보딩 대상은 $projectName (Android 앱) 코드베이스입니다. 이 온보딩 도구 자체(wiki-agent)의 구조나 파일을 설명하지 마세요.")
            appendLine()
            appendLine("단계 정보:")
            appendLine("- 이름: ${step.name}")
            appendLine("- Phase: ${step.phase}")
            appendLine("- 예상 소요 기간: ${step.day}")
            appendLine()
            appendLine(depthInstruction(session.level))
            appendLine()
            if (contentBlock.isNotBlank()) {
                appendLine("=== 참고 자료 (출처별로 구분됨) ===")
                appendLine(contentBlock)
                appendLine("=== 끝 ===")
                appendLine()
                appendLine("절대 규칙:")
                appendLine("- 위 참고 자료에 있는 내용만 안내하세요. 자료에 없는 내용을 추가하거나 추측하지 마세요.")
                appendLine("- 파일 경로, 클래스명, 모듈명은 참고 자료(💻 코드 / 📁 소스파일 포함)에 명시된 것만 사용하세요.")
                appendLine("- 참고 자료의 테이블/목록은 그대로 Slack mrkdwn 형식으로 변환하세요.")
            } else {
                appendLine("참고 자료를 수집하지 못했습니다.")
                appendLine("\"현재 이 단계의 참고 자료를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.\"라고만 안내하세요.")
                appendLine("절대로 자체적으로 내용을 생성하지 마세요.")
            }
            appendLine()
            appendLine("가이드 작성 규칙 (이 구조로 작성):")
            appendLine("1. *핵심 요약* — 이 단계에서 알아야 할 것을 2~3줄로.")
            appendLine("2. *상세* — 참고 자료의 핵심을 불릿으로 정리. 코드 출처가 있으면 실제 클래스/경로를 인용.")
            appendLine("3. *실습 액션* — 참고 자료에 있는 액션 아이템이 있으면 제시.")
            appendLine("4. 컨벤션/규칙 단계는 :white_check_mark: DO / :x: DON'T 형식으로 정리.")
            appendLine("5. 마지막에 `다음`을 입력하면 다음 단계로, 궁금하면 질문하라고 안내.")
        }

        val guideBody = callLLM(guidePrompt)
        return "$header\n\n$guideBody"
    }

    private fun depthInstruction(level: UserLevel?): String = when (level?.android) {
        "A" -> "설명 깊이: 입문자 대상. 배경·용어를 풀어서 상세히 설명하고, 왜 필요한지부터 알려주세요."
        "C" -> "설명 깊이: 숙련자 대상. 요점과 kurly 고유 컨벤션 위주로 간결하게. 일반적인 Android 개념 설명은 생략하세요."
        else -> "설명 깊이: 중급자 대상. 핵심 위주로 설명하고 익숙한 개념은 생략하세요."
    }

    private fun formatProgress(session: OnboardingSession): String {
        return buildString {
            appendLine(":bar_chart: *온보딩 진행 현황*")
            appendLine()

            val grouped = session.steps.groupBy { it.phase }
            var globalIndex = 0
            for (phase in grouped.keys.sorted()) {
                val steps = grouped[phase] ?: continue
                val phaseName = PHASE_NAMES[phase] ?: "Phase $phase"
                val completed = steps.count { it.status == StepStatusType.COMPLETED }
                val skipped = steps.count { it.status == StepStatusType.SKIPPED }
                val total = steps.size
                val done = completed + skipped

                appendLine("*Phase $phase: $phaseName* ($done/$total)")

                for (step in steps) {
                    globalIndex++
                    val icon = when (step.status) {
                        StepStatusType.COMPLETED -> ":white_check_mark:"
                        StepStatusType.SKIPPED -> ":fast_forward:"
                        StepStatusType.PENDING -> if (step.id == session.currentStepId) ":point_right:" else ":white_circle:"
                    }
                    val dateSuffix = step.completedAt?.let { " ($it)" } ?: ""
                    appendLine("  $icon `$globalIndex` ${step.name}$dateSuffix")
                }
                appendLine()
            }

            val totalCompleted = session.steps.count { it.status == StepStatusType.COMPLETED }
            val totalSkipped = session.steps.count { it.status == StepStatusType.SKIPPED }
            val totalSteps = session.steps.size
            val pct = if (totalSteps > 0) ((totalCompleted + totalSkipped) * 100 / totalSteps) else 0
            appendLine("*전체 진행률: $pct%* ($totalCompleted 완료, $totalSkipped 건너뜀 / $totalSteps 전체)")
            appendLine()
            appendLine("_번호(예: `5`) 또는 단계 이름을 입력하면 해당 단계로 이동합니다._")
        }.trim()
    }

    private fun callLLM(prompt: String): String {
        val params = if (model.provider == AnthropicModels.Haiku_4_5.provider) {
            AnthropicParams(maxTokens = 4096)
        } else {
            LLMParams(maxTokens = 4096)
        }

        return runBlocking {
            runCatching {
                executor.execute(
                    prompt("onboarding", params = params) { user(prompt) }, model
                ).joinToString("") { it.content }.trim()
            }.getOrElse { e ->
                log.error("Onboarding LLM call failed: {}", e.message)
                "가이드 생성 중 오류가 발생했습니다: ${e.message}"
            }
        }
    }

    private fun parseLevelResponse(input: String): UserLevel? {
        val parts = input.uppercase()
            .split(Regex("[,\\s]+"))
            .filter { it.isNotBlank() }

        if (parts.size != 3) return null

        val android = parts[0]
        val compose = parts[1]
        val domain = parts[2]

        // Validate: android A/B/C, compose A/B, domain A/B
        if (android !in listOf("A", "B", "C")) return null
        if (compose !in listOf("A", "B")) return null
        if (domain !in listOf("A", "B")) return null

        return UserLevel(android = android, compose = compose, domain = domain)
    }

    companion object {
        const val IOS_REFERENCE_RULE =
            "- [🍎 iOS 참조]·[🔀 Android·iOS 공통] 표시 자료는 iOS 또는 공통 플랫폼 내용입니다. " +
            "Android 온보딩 답변의 권위 있는 출처로 쓰지 말고, 필요 시 \"(iOS 참조)\"로만 인용하세요. SSOT는 📄 위키 자료입니다."

        private val LEVEL_PATTERN = Regex("""^[ABCabc][,\s]+[ABab][,\s]+[ABab]$""")

        private val NEXT_KEYWORDS = setOf("다음", "넘어가기", "다음 단계", "next")
        private val SKIP_KEYWORDS = setOf("건너뛰기", "스킵", "skip")
        private val JUMP_NUMBER_PATTERN = Regex("""^(\d+)\s*번?$""")

        private val PHASE_NAMES = mapOf(
            1 to "환경 셋업 & 프로젝트 구조",
            2 to "도메인 용어 & Compose 컨벤션",
            3 to "브랜치 / QA / 배포 / 모니터링",
            4 to "첫 PR과 코드 리뷰",
            5 to "Claude 스킬 & CI/CD",
        )

        private const val SLACK_FORMAT_RULE = """[출력 형식: Slack mrkdwn — 이 규칙을 최우선으로 준수]
허용: *굵게* _기울임_ ~취소선~ `코드` :emoji: • 불릿 1. 번호
금지: # ## ### **굵게** --- |테이블| [링크](url)
굵게는 *한 개*로 감싼다. 표는 • 불릿으로 대체한다."""

        const val LEVEL_CHECK_MESSAGE = """:wave: 안녕하세요! 온보딩 가이드를 시작합니다.

먼저 경험 수준을 파악할게요. 아래 3가지 질문에 해당하는 레벨을 *한 줄*로 답해주세요.

*1. Android 개발 경험*
• A — 1년 미만 (입문)
• B — 1~3년 (중급)
• C — 3년 이상 (숙련)

*2. Compose 경험*
• A — 거의 없음
• B — 프로젝트 적용 경험 있음

*3. 도메인(커머스) 경험*
• A — 거의 없음
• B — 커머스 or 유사 도메인 경험 있음

:pencil2: 예시: `B, A, A`"""

        const val CANNED_RESPONSE = """:books: *온보딩 가이드*

kurly-android 프로젝트 온보딩을 도와드릴게요!
경험 수준에 맞춰 커리큘럼을 구성해 드립니다.

`온보딩 시작`을 입력하면 시작됩니다."""

        private const val COMPLETION_MESSAGE = """:tada: *온보딩 완료!*

모든 단계를 마쳤습니다. 수고하셨어요!
궁금한 점이 있으면 언제든 질문해 주세요.

`진행률`을 입력하면 전체 진행 현황을 확인할 수 있습니다."""
    }
}
