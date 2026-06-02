# Onboarding Agent Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** kurly-android 신규 입사자를 위한 단계별 온보딩 가이드 에이전트를 wiki-agent에 통합한다.

**Architecture:** OrchestratorAgent 안에서 OnboardingTool을 manual loop 전용으로 직접 호출 (ProgressAdvisorTool 패턴). 커리큘럼은 YAML로 정의하고, 사용자별 진행 상태는 MD 파일로 관리. Slack Block Kit 버튼으로 인터랙션.

**Tech Stack:** Kotlin, kaml (YAML), kotlinx-serialization, Slack Block Kit, MultiLLMPromptExecutor

**Design Doc:** `docs/plans/2026-06-01-onboarding-agent-design.md`

---

## Task 1: Curriculum YAML Schema & Parser

커리큘럼 YAML 파일의 데이터 모델과 파싱 로직을 구현한다.

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculum.kt`
- Create: `.wiki/onboarding/curriculum.yaml`

**Step 1: Write OnboardingCurriculum data classes**

```kotlin
package io.github.veronikapj.wiki.onboarding

import kotlinx.serialization.Serializable

@Serializable
data class OnboardingCurriculum(
    val lastUpdated: String,
    val phases: List<CurriculumPhase>
)

@Serializable
data class CurriculumPhase(
    val id: String,
    val name: String,
    val phase: Int,
    val day: String,
    val skippable: Boolean = true,
    val levelFilter: LevelFilter? = null,
    val sources: List<ContentSource>
)

@Serializable
data class LevelFilter(
    val skipWhen: Map<String, String> = emptyMap()
)

@Serializable
data class ContentSource(
    val type: String,   // "static", "confluence", "code"
    val path: String? = null,
    val query: String? = null
)
```

**Step 2: Write YAML parser**

같은 파일 하단에 파서 함수 추가:

```kotlin
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

object CurriculumLoader {
    private val yaml = Yaml(configuration = YamlConfiguration(
        strictMode = false
    ))

    fun load(path: String): OnboardingCurriculum {
        val text = File(path).readText()
        return yaml.decodeFromString(OnboardingCurriculum.serializer(), text)
    }
}
```

**Step 3: Create starter curriculum.yaml**

`.wiki/onboarding/curriculum.yaml`:

```yaml
lastUpdated: "2026-06-01"

phases:
  - id: env-setup
    name: "개발 환경 세팅"
    phase: 1
    day: "Day 1~2"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
    sources:
      - type: static
        path: ".wiki/onboarding/steps/env-setup.md"

  - id: app-build
    name: "앱 빌드 & 실행"
    phase: 1
    day: "Day 1~2"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
    sources:
      - type: static
        path: ".wiki/onboarding/steps/app-build.md"

  - id: project-structure
    name: "프로젝트 구조 & 모듈 맵"
    phase: 1
    day: "Day 1~2"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/project-structure.md"
      - type: code
        query: "settings.gradle 모듈 목록"

  - id: domain-terms
    name: "도메인 용어 사전"
    phase: 2
    day: "Day 3~5"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/domain-terms.md"
      - type: confluence
        query: "컬리 도메인 용어"

  - id: architecture
    name: "아키텍처 패턴"
    phase: 2
    day: "Day 3~5"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
        compose: "B"
    sources:
      - type: static
        path: ".wiki/onboarding/steps/architecture.md"
      - type: confluence
        query: "kurly android 아키텍처 MVVM"

  - id: common-modules
    name: "주요 공통 모듈"
    phase: 2
    day: "Day 3~5"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/common-modules.md"

  - id: compose-convention
    name: "Compose 전환 현황 & 컨벤션"
    phase: 2
    day: "Day 3~5"
    skippable: true
    levelFilter:
      skipWhen:
        compose: "B"
    sources:
      - type: static
        path: ".wiki/onboarding/steps/compose-convention.md"
      - type: confluence
        query: "kurly compose 전환 가이드"

  - id: branch-convention
    name: "브랜치 전략 & PR 컨벤션"
    phase: 3
    day: "Week 2"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/branch-convention.md"
      - type: confluence
        query: "kurly android 브랜치 전략 PR"

  - id: qa-deploy
    name: "QA / 배포 프로세스"
    phase: 3
    day: "Week 2"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/qa-deploy.md"
      - type: confluence
        query: "kurly android 배포 프로세스"

  - id: monitoring
    name: "모니터링 & 장애 대응"
    phase: 3
    day: "Week 2"
    skippable: true
    levelFilter:
      skipWhen:
        android: "C"
    sources:
      - type: static
        path: ".wiki/onboarding/steps/monitoring.md"

  - id: first-pr
    name: "첫 PR 가이드"
    phase: 4
    day: "Week 2~3"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/first-pr.md"

  - id: code-review
    name: "코드 리뷰 문화 & 체크포인트"
    phase: 4
    day: "Week 2~3"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/code-review.md"

  - id: testing-guide
    name: "테스트 작성 가이드"
    phase: 4
    day: "Week 2~3"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/testing-guide.md"

  - id: first-ticket
    name: "첫 피처 티켓 워크플로우"
    phase: 4
    day: "Week 2~3"
    skippable: false
    sources:
      - type: static
        path: ".wiki/onboarding/steps/first-ticket.md"

  - id: claude-skills
    name: "Claude Code 프로젝트 스킬 활용"
    phase: 5
    day: "Week 3~4"
    skippable: true
    sources:
      - type: static
        path: ".wiki/onboarding/steps/claude-skills.md"

  - id: ci-cd
    name: "CI/CD 자동화 도구 & 린트 룰"
    phase: 5
    day: "Week 3~4"
    skippable: true
    sources:
      - type: static
        path: ".wiki/onboarding/steps/ci-cd.md"
```

**Step 4: Verify YAML parsing compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingCurriculum.kt \
        .wiki/onboarding/curriculum.yaml
git commit -m "feat(onboarding): add curriculum YAML schema and parser"
```

---

## Task 2: OnboardingSession (MD 파일 읽기/쓰기)

사용자별 온보딩 진행 상태를 MD 파일로 관리하는 세션 클래스를 구현한다.

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSession.kt`

**Step 1: Write OnboardingSession data model**

```kotlin
package io.github.veronikapj.wiki.onboarding

import java.io.File
import java.time.LocalDate

data class UserLevel(
    val android: String,  // A, B, C
    val compose: String,  // A, B
    val domain: String    // A, B
)

data class StepStatus(
    val id: String,
    val name: String,
    val phase: Int,
    val status: String,        // "pending", "completed", "skipped"
    val completedAt: String? = null
)

data class OnboardingSession(
    val userId: String,
    val startedAt: String,
    val level: UserLevel?,
    val currentStepId: String?,
    val steps: List<StepStatus>,
    val memos: List<String>
)
```

**Step 2: Write MD file reader/writer**

같은 파일에 `OnboardingSessionStore` 추가:

```kotlin
object OnboardingSessionStore {
    private const val SESSIONS_DIR = ".wiki/onboarding/sessions"

    fun exists(userId: String): Boolean =
        File("$SESSIONS_DIR/$userId.md").exists()

    fun load(userId: String): OnboardingSession? {
        val file = File("$SESSIONS_DIR/$userId.md")
        if (!file.exists()) return null
        return parseMd(userId, file.readText())
    }

    fun save(session: OnboardingSession) {
        val dir = File(SESSIONS_DIR)
        if (!dir.exists()) dir.mkdirs()
        File("$SESSIONS_DIR/${session.userId}.md").writeText(toMd(session))
    }

    fun create(userId: String, level: UserLevel?, steps: List<StepStatus>): OnboardingSession {
        val session = OnboardingSession(
            userId = userId,
            startedAt = LocalDate.now().toString(),
            level = level,
            currentStepId = steps.firstOrNull { it.status == "pending" }?.id,
            steps = steps,
            memos = emptyList()
        )
        save(session)
        return session
    }

    fun addMemo(userId: String, memo: String) {
        val session = load(userId) ?: return
        save(session.copy(memos = session.memos + memo))
    }

    fun advanceStep(userId: String): OnboardingSession? {
        val session = load(userId) ?: return null
        val currentIdx = session.steps.indexOfFirst { it.id == session.currentStepId }
        if (currentIdx < 0) return session

        val updatedSteps = session.steps.toMutableList()
        updatedSteps[currentIdx] = updatedSteps[currentIdx].copy(
            status = "completed",
            completedAt = LocalDate.now().toString()
        )
        val nextStep = updatedSteps.drop(currentIdx + 1).firstOrNull { it.status == "pending" }
        val updated = session.copy(
            steps = updatedSteps,
            currentStepId = nextStep?.id
        )
        save(updated)
        return updated
    }

    fun skipStep(userId: String): OnboardingSession? {
        val session = load(userId) ?: return null
        val currentIdx = session.steps.indexOfFirst { it.id == session.currentStepId }
        if (currentIdx < 0) return session

        val updatedSteps = session.steps.toMutableList()
        updatedSteps[currentIdx] = updatedSteps[currentIdx].copy(status = "skipped")
        val nextStep = updatedSteps.drop(currentIdx + 1).firstOrNull { it.status == "pending" }
        val updated = session.copy(
            steps = updatedSteps,
            currentStepId = nextStep?.id
        )
        save(updated)
        return updated
    }

    fun isActive(userId: String): Boolean {
        val session = load(userId) ?: return false
        return session.currentStepId != null
    }

    private fun toMd(s: OnboardingSession): String = buildString {
        appendLine("# 온보딩 — ${s.userId}")
        appendLine()
        appendLine("## 프로필")
        if (s.level != null) {
            appendLine("- Android: ${s.level.android}")
            appendLine("- Compose: ${s.level.compose}")
            appendLine("- 도메인: ${s.level.domain}")
        }
        appendLine("- 시작일: ${s.startedAt}")
        appendLine()
        appendLine("## 진행 현황")

        var currentPhase = 0
        for (step in s.steps) {
            if (step.phase != currentPhase) {
                currentPhase = step.phase
                // phase header is implicit from step grouping
            }
            val marker = when (step.status) {
                "completed" -> "[x]"
                "skipped" -> "[-]"
                else -> "[ ]"
            }
            val suffix = buildString {
                if (step.completedAt != null) append(" (${step.completedAt})")
                if (step.id == s.currentStepId) append(" ← 현재")
            }
            appendLine("- $marker ${step.name}$suffix")
        }

        if (s.memos.isNotEmpty()) {
            appendLine()
            appendLine("## 메모")
            s.memos.forEach { appendLine("- $it") }
        }
    }

    private fun parseMd(userId: String, text: String): OnboardingSession {
        val lines = text.lines()

        // Parse profile
        var android = ""; var compose = ""; var domain = ""; var startedAt = ""
        var inProfile = false; var inProgress = false; var inMemo = false
        val steps = mutableListOf<StepStatus>()
        val memos = mutableListOf<String>()
        var currentStepId: String? = null

        for (line in lines) {
            when {
                line.startsWith("## 프로필") -> { inProfile = true; inProgress = false; inMemo = false }
                line.startsWith("## 진행 현황") -> { inProfile = false; inProgress = true; inMemo = false }
                line.startsWith("## 메모") -> { inProfile = false; inProgress = false; inMemo = true }
                inProfile && line.startsWith("- Android:") -> android = line.substringAfter(":").trim().take(1)
                inProfile && line.startsWith("- Compose:") -> compose = line.substringAfter(":").trim().take(1)
                inProfile && line.startsWith("- 도메인:") -> domain = line.substringAfter(":").trim().take(1)
                inProfile && line.startsWith("- 시작일:") -> startedAt = line.substringAfter(":").trim()
                inProgress && line.startsWith("- [") -> {
                    val status = when {
                        line.startsWith("- [x]") -> "completed"
                        line.startsWith("- [-]") -> "skipped"
                        else -> "pending"
                    }
                    val isCurrent = line.contains("← 현재")
                    val name = line.substringAfter("] ").replace(" ← 현재", "")
                        .replace(Regex(" \\(\\d{4}-\\d{2}-\\d{2}\\)"), "")
                        .trim()
                    val completedAt = Regex("\\((\\d{4}-\\d{2}-\\d{2})\\)").find(line)?.groupValues?.get(1)
                    // id is derived from name — will be matched against curriculum
                    steps.add(StepStatus(id = "", name = name, phase = 0, status = status, completedAt = completedAt))
                    if (isCurrent || status == "pending" && currentStepId == null) {
                        currentStepId = name  // temporary, will resolve to id
                    }
                }
                inMemo && line.startsWith("- ") -> memos.add(line.removePrefix("- "))
            }
        }

        val level = if (android.isNotEmpty()) UserLevel(android, compose, domain) else null
        return OnboardingSession(userId, startedAt, level, currentStepId, steps, memos)
    }
}
```

> **Note:** `parseMd`의 step id/phase 매칭은 Task 3에서 curriculum과 연동할 때 보강한다. 여기서는 기본 read/write 동작에 집중.

**Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingSession.kt
git commit -m "feat(onboarding): add session MD file store"
```

---

## Task 3: OnboardingTool Core Logic

메시지 의도 분류, 콘텐츠 수집, LLM 가이드 생성을 담당하는 OnboardingTool을 구현한다.

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt`

**Step 1: Write OnboardingTool skeleton**

ProgressAdvisorTool 패턴을 따라 구현:

```kotlin
package io.github.veronikapj.wiki.onboarding

import ai.koog.prompt.executor.model.PromptExecutorExt.execute
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.github.veronikapj.wiki.agent.MultiLLMPromptExecutor
import io.github.veronikapj.wiki.agent.SourceTracker
import io.github.veronikapj.wiki.search.ConfluenceTool
import io.github.veronikapj.wiki.search.CodeSearchTool
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

class OnboardingTool(
    private val curriculumPath: String = ".wiki/onboarding/curriculum.yaml",
    private val executor: MultiLLMPromptExecutor,
    private val model: LLModel,
    private val confluenceTool: ConfluenceTool? = null,
    private val codeSearchTool: CodeSearchTool? = null,
    private val tracker: SourceTracker? = null
) {
    private val log = LoggerFactory.getLogger(OnboardingTool::class.java)
    private val curriculum: OnboardingCurriculum by lazy {
        runCatching { CurriculumLoader.load(curriculumPath) }
            .getOrElse { e ->
                log.error("Failed to load curriculum: {}", e.message)
                OnboardingCurriculum(lastUpdated = "", phases = emptyList())
            }
    }

    fun handle(userId: String, message: String, conversationContext: String = ""): String {
        val intent = classifyIntent(message)
        log.info("Onboarding intent={} userId={}", intent, userId)

        return when (intent) {
            Intent.START -> handleStart(userId)
            Intent.LEVEL_RESPONSE -> handleLevelResponse(userId, message)
            Intent.NEXT -> handleNext(userId)
            Intent.SKIP -> handleSkip(userId)
            Intent.PROGRESS -> handleProgress(userId)
            Intent.REVISIT -> handleRevisit(userId, message)
            Intent.QUESTION -> handleQuestion(userId, message, conversationContext)
        }
    }

    private enum class Intent {
        START, LEVEL_RESPONSE, NEXT, SKIP, PROGRESS, REVISIT, QUESTION
    }

    private fun classifyIntent(message: String): Intent {
        val m = message.trim().lowercase()
        return when {
            m.contains("온보딩") && (m.contains("시작") || m.contains("이어")) -> Intent.START
            m.matches(Regex("[ABC],?\\s*[ABC],?\\s*[ABC]", RegexOption.IGNORE_CASE)) -> Intent.LEVEL_RESPONSE
            m in setOf("다음", "넘어가기", "다음 단계", "next") -> Intent.NEXT
            m in setOf("건너뛰기", "스킵", "skip") -> Intent.SKIP
            m.contains("진행률") || m.contains("현황") || m.contains("progress") -> Intent.PROGRESS
            m.contains("다시 보여") || m.contains("다시 알려") -> Intent.REVISIT
            else -> Intent.QUESTION
        }
    }

    // --- Intent Handlers ---

    private fun handleStart(userId: String): String {
        val session = OnboardingSessionStore.load(userId)
        if (session != null && session.currentStepId != null) {
            // 기존 세션 이어가기
            val step = findCurrentStep(session)
            return if (step != null) {
                generateGuide(userId, step, session)
            } else {
                "온보딩이 이미 완료되었습니다! :tada:"
            }
        }
        // 새 세션 — 레벨 체크 질문
        return LEVEL_CHECK_MESSAGE
    }

    private fun handleLevelResponse(userId: String, message: String): String {
        val parts = message.trim().uppercase().replace(",", " ").split(Regex("\\s+"))
        if (parts.size < 3) return "A/B/C 형태로 세 가지를 입력해주세요. 예: B, A, A"

        val level = UserLevel(
            android = parts[0],
            compose = parts[1],
            domain = parts[2]
        )
        val steps = buildStepsForLevel(level)
        val session = OnboardingSessionStore.create(userId, level, steps)

        val firstStep = curriculum.phases.firstOrNull { it.id == session.currentStepId }
            ?: return "커리큘럼을 불러올 수 없습니다."

        return ":white_check_mark: 레벨 설정 완료!\n\n" + generateGuide(userId, firstStep, session)
    }

    private fun handleNext(userId: String): String {
        val session = OnboardingSessionStore.advanceStep(userId) ?: return "온보딩 세션이 없습니다."
        if (session.currentStepId == null) {
            return ":tada: *온보딩을 모두 완료했습니다!* 수고하셨어요.\n\n언제든 궁금한 점이 있으면 질문해주세요."
        }
        val step = findCurrentStep(session) ?: return "다음 단계를 찾을 수 없습니다."
        return generateGuide(userId, step, session)
    }

    private fun handleSkip(userId: String): String {
        val session = OnboardingSessionStore.skipStep(userId) ?: return "온보딩 세션이 없습니다."
        if (session.currentStepId == null) {
            return ":tada: *온보딩을 모두 완료했습니다!*"
        }
        val step = findCurrentStep(session) ?: return "다음 단계를 찾을 수 없습니다."
        return generateGuide(userId, step, session)
    }

    private fun handleProgress(userId: String): String {
        val session = OnboardingSessionStore.load(userId) ?: return "온보딩 세션이 없습니다."
        return formatProgress(session)
    }

    private fun handleRevisit(userId: String, message: String): String {
        val session = OnboardingSessionStore.load(userId) ?: return "온보딩 세션이 없습니다."
        // 메시지에서 단계명 추출하여 해당 단계 가이드 재생성
        val step = curriculum.phases.firstOrNull { message.contains(it.name) }
            ?: return "어떤 단계를 다시 보고 싶으세요? 단계 이름을 포함해서 말해주세요."
        return generateGuide(userId, step, session)
    }

    private fun handleQuestion(userId: String, message: String, conversationContext: String): String {
        val session = OnboardingSessionStore.load(userId)
        val currentStep = session?.let { findCurrentStep(it) }
        val stepContext = currentStep?.let { collectContent(it) } ?: ""

        val prompt = buildString {
            appendLine("당신은 kurly-android 팀의 친절한 시니어 동료입니다.")
            appendLine("현재 신규 입사자의 온보딩을 돕고 있습니다.")
            if (currentStep != null) {
                appendLine("현재 온보딩 단계: ${currentStep.name}")
                appendLine("단계 참고 자료:\n$stepContext")
            }
            if (conversationContext.isNotBlank()) {
                appendLine("이전 대화:\n$conversationContext")
            }
            appendLine()
            appendLine("질문: $message")
            appendLine()
            appendLine("Slack mrkdwn 포맷으로 답변하세요. 간결하게, 한 번에 너무 많은 정보를 주지 마세요.")
        }

        return callLLM(prompt)
    }

    // --- Helper Methods ---

    private fun buildStepsForLevel(level: UserLevel): List<StepStatus> {
        return curriculum.phases.map { phase ->
            val shouldSkip = phase.skippable && phase.levelFilter?.skipWhen?.all { (key, value) ->
                when (key) {
                    "android" -> level.android == value
                    "compose" -> level.compose == value
                    "domain" -> level.domain == value
                    else -> false
                }
            } == true

            StepStatus(
                id = phase.id,
                name = phase.name,
                phase = phase.phase,
                status = if (shouldSkip) "skipped" else "pending"
            )
        }
    }

    private fun findCurrentStep(session: OnboardingSession): CurriculumPhase? {
        val stepId = session.currentStepId ?: return null
        val step = curriculum.phases.firstOrNull { it.id == stepId }
        if (step == null) {
            // 커리큘럼 업데이트로 step 삭제된 경우 → 다음 존재하는 step으로 이동
            log.warn("Step {} not found in curriculum, advancing", stepId)
            val advanced = OnboardingSessionStore.advanceStep(session.userId)
            return advanced?.currentStepId?.let { id -> curriculum.phases.firstOrNull { it.id == id } }
        }
        return step
    }

    private fun generateGuide(userId: String, step: CurriculumPhase, session: OnboardingSession): String {
        val content = collectContent(step)
        val phaseSteps = session.steps.filter { it.phase == step.phase }
        val completedInPhase = phaseSteps.count { it.status == "completed" }
        val totalInPhase = phaseSteps.size

        val prompt = buildString {
            appendLine("당신은 kurly-android 팀의 친절한 시니어 동료입니다.")
            appendLine("신규 입사자에게 온보딩 가이드를 단계별로 전달하고 있습니다.")
            appendLine()
            appendLine("현재 단계: ${step.name}")
            appendLine("참고 자료:")
            appendLine(content)
            appendLine()
            appendLine("위 자료를 바탕으로 신규 입사자에게 이 단계의 핵심 내용을 안내하세요.")
            appendLine("규칙:")
            appendLine("- Slack mrkdwn 포맷")
            appendLine("- 핵심 내용만 간결하게, 300단어 이내")
            appendLine("- 실습 가능한 항목이 있으면 구체적 명령어/경로 제시")
            appendLine("- 트라이벌 놀리지(주의사항/함정)가 있으면 반드시 포함")
        }

        val guideContent = callLLM(prompt)
        val header = ":books: *[Phase ${step.phase}: ${completedInPhase + 1}/${totalInPhase}] ${step.name}* (${step.day})"

        return "$header\n\n$guideContent"
    }

    private fun collectContent(step: CurriculumPhase): String = buildString {
        for (source in step.sources.sortedBy {
            when (it.type) {
                "static" -> 0
                "code" -> 1
                "confluence" -> 2
                else -> 3
            }
        }) {
            when (source.type) {
                "static" -> {
                    val file = File(source.path ?: continue)
                    if (file.exists()) {
                        appendLine("--- static source: ${source.path} ---")
                        appendLine(file.readText())
                    }
                }
                "confluence" -> {
                    if (confluenceTool != null && source.query != null) {
                        runCatching {
                            val result = runBlocking { confluenceTool.search(source.query) }
                            if (result.isNotBlank()) {
                                appendLine("--- confluence source ---")
                                appendLine(result.take(2000))
                            }
                        }.onFailure { log.warn("Confluence search failed: {}", it.message) }
                    }
                }
                "code" -> {
                    if (codeSearchTool != null && source.query != null) {
                        runCatching {
                            val result = runBlocking { codeSearchTool.search(source.query) }
                            if (result.isNotBlank()) {
                                appendLine("--- code source ---")
                                appendLine(result.take(2000))
                            }
                        }.onFailure { log.warn("Code search failed: {}", it.message) }
                    }
                }
            }
        }
    }

    private fun formatProgress(session: OnboardingSession): String = buildString {
        appendLine(":bar_chart: *온보딩 진행 현황*")
        appendLine()

        val phaseNames = mapOf(
            1 to "환경 & 기본기",
            2 to "도메인 & 코드 이해",
            3 to "프로세스",
            4 to "실전",
            5 to "스킬 가이드"
        )

        var currentPhase = 0
        for (step in session.steps) {
            if (step.phase != currentPhase) {
                currentPhase = step.phase
                val phaseSteps = session.steps.filter { it.phase == currentPhase }
                val done = phaseSteps.count { it.status in setOf("completed", "skipped") }
                appendLine("*Phase $currentPhase — ${phaseNames[currentPhase] ?: ""}* ($done/${phaseSteps.size})")
            }
            val icon = when (step.status) {
                "completed" -> ":white_check_mark:"
                "skipped" -> ":fast_forward:"
                else -> if (step.id == session.currentStepId) ":large_blue_circle:" else ":white_large_square:"
            }
            val suffix = if (step.id == session.currentStepId) " ← 현재" else ""
            appendLine("  $icon ${step.name}$suffix")
        }

        val activeSteps = session.steps.filter { it.status != "skipped" }
        val done = activeSteps.count { it.status == "completed" }
        appendLine()

        val currentPhaseSteps = session.steps.filter { it.phase == (session.steps.firstOrNull { s -> s.id == session.currentStepId }?.phase ?: 0) }
        val phaseDone = currentPhaseSteps.count { it.status in setOf("completed", "skipped") }
        if (currentPhaseSteps.isNotEmpty()) {
            appendLine("현재 Phase 진행률: $phaseDone/${currentPhaseSteps.size}")
        }
    }

    private fun callLLM(prompt: String): String {
        return runCatching {
            runBlocking {
                executor.execute(prompt, model, LLMParams.Anthropic(maxTokens = 2048))
            }
        }.getOrElse { e ->
            log.error("LLM call failed: {}", e.message)
            "가이드 생성 중 오류가 발생했습니다: ${e.message}"
        }
    }

    companion object {
        val LEVEL_CHECK_MESSAGE = """
            :wave: *온보딩 가이드를 시작할게요!*

            먼저 경험을 알려주세요 (예: `B, A, A`):

            1️⃣ *Android 개발 경험*
               A) 처음이에요  B) 1~2년  C) 3년 이상

            2️⃣ *Compose 프로덕션 배포 경험*
               A) 없음  B) 있음

            3️⃣ *커머스 도메인 경험*
               A) 없어요  B) 있어요
        """.trimIndent()

        val CANNED_RESPONSE = """
            :rocket: *온보딩 가이드*

            신규 입사자를 위한 kurly-android 프로젝트 온보딩을 도와드려요.
            개발 환경부터 첫 피처 티켓까지, 단계별로 안내합니다.

            *시작하려면* "온보딩 시작"이라고 입력하세요!
        """.trimIndent()
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (ConfluenceTool/CodeSearchTool 시그니처가 다르면 조정 필요)

**Step 3: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingTool.kt
git commit -m "feat(onboarding): add OnboardingTool core logic"
```

---

## Task 4: OrchestratorAgent 통합

OnboardingTool을 OrchestratorAgent의 manual loop에 통합한다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`

**Step 1: Add OnboardingTool to constructor**

`OrchestratorAgent.kt` 생성자에 파라미터 추가:

```kotlin
class OrchestratorAgent(
    // ... 기존 파라미터들 ...
    private val progressAdvisorTool: ProgressAdvisorTool? = null,
    private val onboardingTool: OnboardingTool? = null,  // 추가
    // ...
)
```

**Step 2: Add pre-routing for onboarding**

기존 progressAdvisor 프리라우팅 로직 근처 (약 242~258줄)에 온보딩 라우팅 추가:

```kotlin
// onboarding pre-routing: forceTool이 "onboarding"이면 바로 처리
if (forceTool == "onboarding" && onboardingTool != null) {
    // forceTool 경로로 직접 진입
}
```

**Step 3: Add manual loop handling**

기존 progressAdvisor 처리 (344~360줄) 아래에 온보딩 처리 추가:

```kotlin
if (toolName == "onboarding" && onboardingTool != null) {
    listener?.onSearchStarted("onboarding")
    val conversationContext = if (sessionId != null && conversationStore != null) {
        conversationStore.load(sessionId, maxTurns = 3).joinToString("\n") { "${it.role}: ${it.content}" }
    } else ""

    val onboardingAnswer = runCatching {
        onboardingTool!!.handle(userId ?: "", question, conversationContext)
    }.getOrElse { e ->
        log.error("Onboarding failed: {}", e.message)
        "온보딩 가이드 생성 중 오류가 발생했습니다: ${e.message}"
    }
    listener?.onSearchCompleted("onboarding")

    if (sessionId != null && conversationStore != null) {
        conversationStore.append(sessionId, question, onboardingAnswer)
    }
    return AnswerResult(onboardingAnswer, "MANUAL", false)
}
```

**Step 4: Add router label**

TOOL_LABELS 맵 (또는 라우터 프롬프트)에 "onboarding" → "온보딩 가이드" 추가.

**Step 5: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git commit -m "feat(onboarding): integrate OnboardingTool into OrchestratorAgent"
```

---

## Task 5: SlackBotGateway 통합

SUGGESTED_PROMPTS 추가, forceTool 바인딩, Block Kit 버튼 처리를 구현한다.

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt`

**Step 1: Add 5th SUGGESTED_PROMPT**

`SUGGESTED_PROMPTS` (752줄 근처)에 추가:

```kotlin
SuggestedPrompt.builder().title("온보딩 가이드 시작").message("온보딩 가이드 시작").build(),
```

**Step 2: Add HINT_FORCED_TOOL mapping**

`HINT_FORCED_TOOL` (759줄 근처)에 추가:

```kotlin
"온보딩 가이드 시작" to "onboarding",
```

**Step 3: Add CANNED_RESPONSES entry**

`CANNED_RESPONSES` (766줄 근처)에 추가:

```kotlin
"온보딩 가이드 시작" to OnboardingTool.CANNED_RESPONSE,
```

> **Note:** CANNED_RESPONSE는 온보딩 소개 메시지만 보여주고, 사용자가 "온보딩 시작"이라고 입력하면 OnboardingTool.handle()이 실제 처리.

**Step 4: Add session-based forceTool binding**

메시지 핸들러 (623줄 근처)에서 온보딩 세션 활성 체크 추가:

```kotlin
// 기존 forceTool 결정 로직 이후
val effectiveForceTool = forceTool
    ?: if (OnboardingSessionStore.isActive(userId)) "onboarding" else null
```

**Step 5: Add Block Kit button handler**

`app.blockAction` 핸들러 등록 (registerAssistantHandler 내부 또는 별도):

```kotlin
// 온보딩 Block Kit 버튼 처리
app.blockAction("onboarding_next") { req, ctx ->
    // "다음" 버튼 클릭 → 온보딩 메시지로 처리
    val userId = req.payload.user.id
    val channel = req.payload.channel.id
    val threadTs = req.payload.message.threadTs ?: req.payload.message.ts
    handleOnboardingAction(userId, channel, threadTs, "다음")
    ctx.ack()
}
app.blockAction("onboarding_skip") { req, ctx ->
    val userId = req.payload.user.id
    val channel = req.payload.channel.id
    val threadTs = req.payload.message.threadTs ?: req.payload.message.ts
    handleOnboardingAction(userId, channel, threadTs, "건너뛰기")
    ctx.ack()
}
app.blockAction("onboarding_progress") { req, ctx ->
    val userId = req.payload.user.id
    val channel = req.payload.channel.id
    val threadTs = req.payload.message.threadTs ?: req.payload.message.ts
    handleOnboardingAction(userId, channel, threadTs, "진행률")
    ctx.ack()
}
```

**Step 6: Add Block Kit message builder utility**

온보딩 가이드 메시지에 버튼을 붙이는 유틸:

```kotlin
private fun appendOnboardingButtons(text: String): List<LayoutBlock> {
    val blocks = mutableListOf<LayoutBlock>()
    blocks.add(SectionBlock.builder().text(MarkdownTextObject("text", text)).build())
    blocks.add(ActionsBlock.builder().elements(listOf(
        ButtonElement.builder().text(PlainTextObject("다음 ➡️", false)).actionId("onboarding_next").build(),
        ButtonElement.builder().text(PlainTextObject("건너뛰기 ⏭", false)).actionId("onboarding_skip").build(),
        ButtonElement.builder().text(PlainTextObject("진행률 📊", false)).actionId("onboarding_progress").build(),
    )).build())
    return blocks
}
```

**Step 7: Add TOOL_DISPLAY_NAMES entry**

기존 Tool 한글 라벨 맵에 추가:

```kotlin
"onboarding" to "온보딩 가이드"
```

**Step 8: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackBotGateway.kt
git commit -m "feat(onboarding): integrate into SlackBotGateway with Block Kit buttons"
```

---

## Task 6: OnboardingTool Wiring (DI)

OnboardingTool 인스턴스를 생성하고 OrchestratorAgent에 주입하는 코드를 추가한다.

**Files:**
- Modify: WikiConfig 또는 main 진입점 (OnboardingTool 인스턴스 생성 위치)

**Step 1: Find instantiation point**

OrchestratorAgent와 ProgressAdvisorTool이 생성되는 위치를 확인하고, 같은 패턴으로 OnboardingTool 생성.

```kotlin
val onboardingTool = OnboardingTool(
    curriculumPath = ".wiki/onboarding/curriculum.yaml",
    executor = executor,
    model = LLModel.Haiku_4_5,
    confluenceTool = confluenceTool,
    codeSearchTool = codeSearchTool
)

val orchestrator = OrchestratorAgent(
    // ... 기존 파라미터 ...
    onboardingTool = onboardingTool,
)
```

**Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A  # 변경된 DI 파일
git commit -m "feat(onboarding): wire OnboardingTool into DI"
```

---

## Task 7: Static Content Stubs

각 커리큘럼 단계의 starter static MD 파일을 생성한다. 실제 콘텐츠는 추후 작성하고, 여기서는 구조만 잡는다.

**Files:**
- Create: `.wiki/onboarding/steps/env-setup.md`
- Create: `.wiki/onboarding/steps/app-build.md`
- Create: `.wiki/onboarding/steps/project-structure.md`
- Create: `.wiki/onboarding/steps/domain-terms.md`
- Create: `.wiki/onboarding/steps/architecture.md`
- Create: `.wiki/onboarding/steps/common-modules.md`
- Create: `.wiki/onboarding/steps/compose-convention.md`
- Create: `.wiki/onboarding/steps/branch-convention.md`
- Create: `.wiki/onboarding/steps/qa-deploy.md`
- Create: `.wiki/onboarding/steps/monitoring.md`
- Create: `.wiki/onboarding/steps/first-pr.md`
- Create: `.wiki/onboarding/steps/code-review.md`
- Create: `.wiki/onboarding/steps/testing-guide.md`
- Create: `.wiki/onboarding/steps/first-ticket.md`
- Create: `.wiki/onboarding/steps/claude-skills.md`
- Create: `.wiki/onboarding/steps/ci-cd.md`

**Step 1: Create stub files**

각 파일에 동일한 구조로 stub 작성:

```markdown
---
last_updated: 2026-06-01
status: stub
---

# [단계명]

> TODO: 실제 콘텐츠 작성 필요

## 핵심 내용

(작성 예정)

## 주의사항 / 트라이벌 놀리지

(해당 시 작성)
```

**Step 2: Commit**

```bash
git add .wiki/onboarding/steps/
git commit -m "feat(onboarding): add static content stub files for all 16 steps"
```

---

## Task 8: Integration Smoke Test

실제 Slack 연동 전 로컬에서 OnboardingTool의 기본 흐름을 검증한다.

**Files:**
- Create: `src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt`

**Step 1: Write basic flow test**

```kotlin
package io.github.veronikapj.wiki.onboarding

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OnboardingSessionStoreTest {
    @Test
    fun `세션 생성 후 MD 파일이 존재한다`() {
        // Given
        val steps = listOf(
            StepStatus("env-setup", "개발 환경 세팅", 1, "pending"),
            StepStatus("app-build", "앱 빌드", 1, "pending"),
        )

        // When
        val session = OnboardingSessionStore.create("U_TEST", UserLevel("B", "A", "A"), steps)

        // Then
        assertTrue(OnboardingSessionStore.exists("U_TEST"))
        assertNotNull(session.currentStepId)
    }

    @Test
    fun `다음 단계로 이동하면 현재 단계가 완료된다`() {
        // Given
        val steps = listOf(
            StepStatus("step1", "Step 1", 1, "pending"),
            StepStatus("step2", "Step 2", 1, "pending"),
        )
        OnboardingSessionStore.create("U_ADV", null, steps)

        // When
        val advanced = OnboardingSessionStore.advanceStep("U_ADV")

        // Then
        assertNotNull(advanced)
        assertTrue(advanced!!.steps[0].status == "completed")
        assertTrue(advanced.currentStepId == "step2")
    }

    @Test
    fun `모든 단계 완료 시 currentStepId가 null이다`() {
        val steps = listOf(StepStatus("only", "Only Step", 1, "pending"))
        OnboardingSessionStore.create("U_DONE", null, steps)

        val result = OnboardingSessionStore.advanceStep("U_DONE")

        assertNull(result?.currentStepId)
    }
}
```

**Step 2: Run tests**

Run: `./gradlew test --tests "*.OnboardingSessionStoreTest" -v`
Expected: 3 tests PASS

**Step 3: Commit**

```bash
git add src/test/kotlin/io/github/veronikapj/wiki/onboarding/OnboardingToolTest.kt
git commit -m "test(onboarding): add session store unit tests"
```

---

## Task Summary

| Task | 설명 | 예상 파일 수 |
|------|------|------------|
| 1 | Curriculum YAML Schema & Parser | 2 |
| 2 | OnboardingSession MD Store | 1 |
| 3 | OnboardingTool Core Logic | 1 |
| 4 | OrchestratorAgent 통합 | 1 (modify) |
| 5 | SlackBotGateway 통합 | 1 (modify) |
| 6 | DI Wiring | 1 (modify) |
| 7 | Static Content Stubs | 16 |
| 8 | Integration Smoke Test | 1 |

**의존 관계:** Task 1 → Task 2 → Task 3 → Task 4 + Task 5 (병렬 가능) → Task 6 → Task 7 → Task 8
