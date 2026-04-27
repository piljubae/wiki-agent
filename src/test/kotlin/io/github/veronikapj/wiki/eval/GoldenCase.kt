package io.github.veronikapj.wiki.eval

import kotlinx.serialization.Serializable

@Serializable
data class GoldenCase(
    val id: String,
    val question: String,
    val category: Category,
    val expectedDocTitles: List<String>,
    val keyPoints: List<String> = emptyList(),
    val negativePoints: List<String> = emptyList(),
    // 답변 품질 필드
    val questionType: QuestionType = QuestionType.DEFINITION,
    val expectedMinLines: Int = 3,
    val expectedMaxLines: Int = 8,
    val requiresSteps: Boolean = false,
    val requiresLink: Boolean = true,
    val sourcePageId: String? = null,
)

@Serializable
enum class Category {
    EXACT_MATCH,
    SYNONYM_GAP,
    ABBREVIATION,
    PARTIAL_MATCH,
    MULTI_DOC,
    ZERO_EXPECTED,
    // 자동 생성 카테고리
    TITLE_BASED,
    LLM_GENERATED,
    PARAPHRASE,
}

@Serializable
enum class QuestionType {
    DEFINITION,
    PROCEDURE,
    COMPOSITE,
    ZERO,
}
