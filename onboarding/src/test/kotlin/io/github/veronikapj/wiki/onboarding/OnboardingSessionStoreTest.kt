package io.github.veronikapj.wiki.onboarding

import org.junit.jupiter.api.AfterEach
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingSessionStoreTest {

    private val testUserPrefix = "U_TEST_"
    private fun uniqueUserId() = "$testUserPrefix${System.nanoTime()}"

    private fun twoSteps() = listOf(
        StepStatus(id = "step-1", name = "환경 세팅", phase = 1, status = StepStatusType.PENDING),
        StepStatus(id = "step-2", name = "첫 PR 리뷰", phase = 2, status = StepStatusType.PENDING),
    )

    private fun oneStep() = listOf(
        StepStatus(id = "step-1", name = "환경 세팅", phase = 1, status = StepStatusType.PENDING),
    )

    private val testLevel = UserLevel(android = "B", compose = "A", domain = "B")

    @AfterEach
    fun cleanup() {
        File(".wiki/onboarding/sessions/").listFiles()
            ?.filter { it.name.startsWith(testUserPrefix) }
            ?.forEach { it.delete() }
    }

    @Test
    fun `세션 생성 후 파일이 존재한다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, twoSteps())
        assertTrue(OnboardingSessionStore.exists(userId))
    }

    @Test
    fun `다음 단계로 이동하면 현재 단계가 완료된다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, twoSteps())

        val advanced = OnboardingSessionStore.advanceStep(userId)
        assertNotNull(advanced)
        assertEquals(StepStatusType.COMPLETED, advanced.steps[0].status)
        assertNotNull(advanced.steps[0].completedAt)
        assertEquals("step-2", advanced.currentStepId)
        assertEquals(StepStatusType.PENDING, advanced.steps[1].status)
    }

    @Test
    fun `모든 단계 완료 시 currentStepId가 null이다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, oneStep())

        val advanced = OnboardingSessionStore.advanceStep(userId)
        assertNotNull(advanced)
        assertNull(advanced.currentStepId)
        assertEquals(StepStatusType.COMPLETED, advanced.steps[0].status)
    }

    @Test
    fun `건너뛰기 시 상태가 SKIPPED로 변경된다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, twoSteps())

        val skipped = OnboardingSessionStore.skipStep(userId)
        assertNotNull(skipped)
        assertEquals(StepStatusType.SKIPPED, skipped.steps[0].status)
        assertEquals("step-2", skipped.currentStepId)
    }

    @Test
    fun `메모 추가 후 로드하면 메모가 포함된다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, twoSteps())

        OnboardingSessionStore.addMemo(userId, "Gradle sync 완료")
        val loaded = OnboardingSessionStore.load(userId)
        assertNotNull(loaded)
        assertEquals(1, loaded.memos.size)
        assertEquals("Gradle sync 완료", loaded.memos[0])
    }

    @Test
    fun `MD 파일 round-trip 시 데이터가 보존된다`() {
        val today = LocalDate.now().toString()
        val session = OnboardingSession(
            userId = "roundtrip-user",
            startedAt = today,
            level = UserLevel(android = "A", compose = "B", domain = "A"),
            currentStepId = "step-2",
            steps = listOf(
                StepStatus(id = "step-1", name = "환경 세팅", phase = 1, status = StepStatusType.COMPLETED, completedAt = today),
                StepStatus(id = "step-2", name = "첫 PR 리뷰", phase = 2, status = StepStatusType.PENDING),
                StepStatus(id = "step-3", name = "도메인 학습", phase = 3, status = StepStatusType.SKIPPED),
            ),
            memos = listOf("첫 번째 메모", "두 번째 메모"),
        )

        val md = OnboardingSessionStore.toMd(session)
        val parsed = OnboardingSessionStore.parseMd(session.userId, md)

        assertEquals(session.userId, parsed.userId)
        assertEquals(session.startedAt, parsed.startedAt)
        assertEquals(session.level, parsed.level)
        assertEquals(session.currentStepId, parsed.currentStepId)
        assertEquals(session.steps.size, parsed.steps.size)
        for (i in session.steps.indices) {
            assertEquals(session.steps[i].id, parsed.steps[i].id)
            assertEquals(session.steps[i].name, parsed.steps[i].name)
            assertEquals(session.steps[i].phase, parsed.steps[i].phase)
            assertEquals(session.steps[i].status, parsed.steps[i].status)
            assertEquals(session.steps[i].completedAt, parsed.steps[i].completedAt)
        }
        assertEquals(session.memos, parsed.memos)
    }

    @Test
    fun `비활성 세션은 isActive가 false를 반환한다`() {
        val userId = uniqueUserId()
        OnboardingSessionStore.create(userId, testLevel, oneStep())

        assertTrue(OnboardingSessionStore.isActive(userId))

        OnboardingSessionStore.advanceStep(userId)

        assertFalse(OnboardingSessionStore.isActive(userId))
    }
}
