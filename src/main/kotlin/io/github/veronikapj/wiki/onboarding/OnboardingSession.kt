package io.github.veronikapj.wiki.onboarding

import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

data class UserLevel(
    val android: String,  // A, B, C
    val compose: String,  // A, B
    val domain: String,   // A, B
)

data class StepStatus(
    val id: String,
    val name: String,
    val phase: Int,
    val status: String,        // "pending", "completed", "skipped"
    val completedAt: String? = null,
)

data class OnboardingSession(
    val userId: String,
    val startedAt: String,
    val level: UserLevel?,
    val currentStepId: String?,
    val steps: List<StepStatus>,
    val memos: List<String>,
)

object OnboardingSessionStore {
    private val log = LoggerFactory.getLogger(OnboardingSessionStore::class.java)
    private const val SESSIONS_DIR = ".wiki/onboarding/sessions"

    private fun sessionFile(userId: String): File =
        File(SESSIONS_DIR, "$userId.md")

    fun exists(userId: String): Boolean =
        sessionFile(userId).exists()

    fun load(userId: String): OnboardingSession? {
        val file = sessionFile(userId)
        if (!file.exists()) return null
        return runCatching {
            parseMd(userId, file.readText())
        }.onFailure { e ->
            log.error("Failed to parse session for {}: {}", userId, e.message)
        }.getOrNull()
    }

    fun save(session: OnboardingSession) {
        val file = sessionFile(session.userId)
        file.parentFile.mkdirs()
        file.writeText(toMd(session))
    }

    fun create(
        userId: String,
        level: UserLevel?,
        steps: List<StepStatus>,
    ): OnboardingSession {
        val currentStepId = steps.firstOrNull { it.status == "pending" }?.id
        val session = OnboardingSession(
            userId = userId,
            startedAt = LocalDate.now().toString(),
            level = level,
            currentStepId = currentStepId,
            steps = steps,
            memos = emptyList(),
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
        val currentId = session.currentStepId ?: return session

        val today = LocalDate.now().toString()
        val updatedSteps = session.steps.map { step ->
            if (step.id == currentId) step.copy(status = "completed", completedAt = today)
            else step
        }
        val nextPending = updatedSteps.firstOrNull { it.status == "pending" }
        val updated = session.copy(
            steps = updatedSteps,
            currentStepId = nextPending?.id,
        )
        save(updated)
        return updated
    }

    fun skipStep(userId: String): OnboardingSession? {
        val session = load(userId) ?: return null
        val currentId = session.currentStepId ?: return session

        val updatedSteps = session.steps.map { step ->
            if (step.id == currentId) step.copy(status = "skipped")
            else step
        }
        val nextPending = updatedSteps.firstOrNull { it.status == "pending" }
        val updated = session.copy(
            steps = updatedSteps,
            currentStepId = nextPending?.id,
        )
        save(updated)
        return updated
    }

    fun isActive(userId: String): Boolean {
        val session = load(userId) ?: return false
        return session.currentStepId != null
    }

    // ── MD serialization ──

    internal fun toMd(session: OnboardingSession): String = buildString {
        appendLine("# 온보딩 — ${session.userId}")
        appendLine()

        // Profile section
        appendLine("## 프로필")
        if (session.level != null) {
            appendLine("- Android: ${session.level.android}")
            appendLine("- Compose: ${session.level.compose}")
            appendLine("- 도메인: ${session.level.domain}")
        }
        appendLine("- 시작일: ${session.startedAt}")
        appendLine()

        // Progress section
        appendLine("## 진행 현황")
        for (step in session.steps) {
            val marker = when (step.status) {
                "completed" -> "x"
                "skipped" -> "-"
                else -> " "
            }
            val dateSuffix = if (step.completedAt != null) " (${step.completedAt})" else ""
            val currentSuffix = if (step.id == session.currentStepId) " ← 현재" else ""
            appendLine("- [$marker] ${step.name} [${step.id}]${dateSuffix}${currentSuffix}")
        }
        appendLine()

        // Memos section
        appendLine("## 메모")
        if (session.memos.isEmpty()) {
            appendLine("(없음)")
        } else {
            for (memo in session.memos) {
                appendLine("- $memo")
            }
        }
    }

    internal fun parseMd(userId: String, content: String): OnboardingSession {
        val lines = content.lines()

        var android: String? = null
        var compose: String? = null
        var domain: String? = null
        var startedAt: String? = null
        var currentStepId: String? = null
        val steps = mutableListOf<StepStatus>()
        val memos = mutableListOf<String>()

        var section = ""

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.startsWith("## ")) {
                section = trimmed.removePrefix("## ").trim()
                continue
            }

            when (section) {
                "프로필" -> {
                    if (trimmed.startsWith("- Android:")) android = trimmed.substringAfter("- Android:").trim()
                    if (trimmed.startsWith("- Compose:")) compose = trimmed.substringAfter("- Compose:").trim()
                    if (trimmed.startsWith("- 도메인:")) domain = trimmed.substringAfter("- 도메인:").trim()
                    if (trimmed.startsWith("- 시작일:")) startedAt = trimmed.substringAfter("- 시작일:").trim()
                }
                "진행 현황" -> {
                    val stepMatch = STEP_PATTERN.matchEntire(trimmed) ?: continue
                    val marker = stepMatch.groupValues[1]
                    val name = stepMatch.groupValues[2].trim()
                    val id = stepMatch.groupValues[3]
                    val completedAt = stepMatch.groupValues[4].ifEmpty { null }
                    val isCurrent = stepMatch.groupValues[5].isNotEmpty()

                    val status = when (marker) {
                        "x" -> "completed"
                        "-" -> "skipped"
                        else -> "pending"
                    }

                    if (isCurrent) currentStepId = id

                    steps.add(
                        StepStatus(
                            id = id,
                            name = name,
                            phase = 0, // phase is not stored in MD; will be enriched from curriculum
                            status = status,
                            completedAt = completedAt,
                        )
                    )
                }
                "메모" -> {
                    if (trimmed.startsWith("- ") && trimmed != "- (없음)") {
                        memos.add(trimmed.removePrefix("- "))
                    }
                }
            }
        }

        val level = if (android != null && compose != null && domain != null) {
            UserLevel(android = android, compose = compose, domain = domain)
        } else null

        return OnboardingSession(
            userId = userId,
            startedAt = startedAt ?: LocalDate.now().toString(),
            level = level,
            currentStepId = currentStepId,
            steps = steps,
            memos = memos,
        )
    }

    // Matches: - [x] 개발 환경 세팅 [env-setup] (2026-06-01) ← 현재
    // Groups: 1=marker  2=name  3=id  4=date (optional)  5=← 현재 (optional)
    private val STEP_PATTERN = Regex(
        """^- \[([x\- ])] (.+?) \[([^\]]+)](?:\s+\((\d{4}-\d{2}-\d{2})\))?(?: (← 현재))?$"""
    )
}
