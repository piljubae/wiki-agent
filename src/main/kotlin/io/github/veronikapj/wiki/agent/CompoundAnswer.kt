package io.github.veronikapj.wiki.agent

data class StepResult(
    val subQuestion: String,
    val toolName: String,
    val searchResult: String?,
)

/** N개 StepResult를 sub-question 라벨 섹션으로 묶어 요약 프롬프트 본문을 만든다. */
fun buildSectionedResultBlock(steps: List<StepResult>): String = buildString {
    steps.forEachIndexed { i, step ->
        appendLine("[${i + 1}. ${step.subQuestion}]")
        appendLine(step.searchResult ?: "관련 문서를 찾지 못했습니다.")
        appendLine()
    }
}.trim()
