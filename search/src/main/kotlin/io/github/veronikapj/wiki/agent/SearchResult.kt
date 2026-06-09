package io.github.veronikapj.wiki.agent

enum class SearchStage(val score: Double) {
    TITLE_MATCH(1.0),
    SPACE_EXPANSION(0.8),
    TEXT_MATCH(0.6),
    KEYWORD_AND(0.55),
    GLOBAL_FALLBACK(0.4),
}

data class SearchResult(
    val pageId: String,
    val title: String,
    val url: String,
    val snippet: String,
    val stage: SearchStage,
)

fun List<SearchResult>.formatForSlack(): String {
    if (isEmpty()) return "관련 문서를 찾을 수 없습니다."
    val sb = StringBuilder()
    sb.appendLine("*검색 결과 (${size}건):*\n")
    forEachIndexed { i, r ->
        sb.appendLine("${i + 1}. *${r.title}*")
        sb.appendLine("   <${r.url}|링크>")
        sb.appendLine("   > ${r.snippet.replace("\n", "\n   > ")}")
        sb.appendLine()
    }
    return sb.toString().trim()
}
