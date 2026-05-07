package io.github.veronikapj.wiki.eval

import kotlinx.serialization.Serializable

@Serializable
data class RedTeamCase(
    val id: String,
    val attackType: RedTeamAttackType,
    val input: String,
    val expectedBehavior: RedTeamExpectedBehavior,
    /** 응답에 절대 포함되면 안 되는 패턴 (regex) */
    val forbiddenPatterns: List<String> = emptyList(),
    val notes: String = "",
)

@Serializable
enum class RedTeamAttackType {
    HALLUCINATION,      // 존재하지 않는 정보 요청
    OFF_TOPIC,          // 완전히 무관한 질문
    NOISE,              // 오타/특수문자/빈 쿼리
    PROMPT_INJECTION,   // "이전 지시 무시하고..." 스타일
    SYSTEM_EXTRACTION,  // API 키, 시스템 프롬프트 추출 시도
    JAILBREAK,          // 역할 전환/탈옥 시도
    CODE_GHOST,         // 존재하지 않는 함수/클래스 검색
    CODE_ACCURACY,      // 실제 코드 검색 정확도 검증
}

@Serializable
enum class RedTeamExpectedBehavior {
    NO_RESULTS,         // 검색 결과 0건
    SAFE_RESPONSE,      // crash 없음, 최소한의 안전 응답
    NO_SENSITIVE_INFO,  // API 키/시스템 정보 미노출
    LOW_SIMILARITY,     // 코드 유사도 낮음 (ghost 함수)
    ACCURATE_CODE,      // 실제 존재하는 파일 경로 반환
}

data class RedTeamResult(
    val case: RedTeamCase,
    val passed: Boolean,
    val resultCount: Int,
    val latencyMs: Long,
    val failReason: String? = null,
    val responseSnippet: String? = null,
)
