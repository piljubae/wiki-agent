package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File

class PersonalDataTool(
    private val progressFile: String,
    private val allowedUsers: Set<String>,
    private val tracker: SourceTracker? = null,
) {
    private val log = LoggerFactory.getLogger(PersonalDataTool::class.java)

    private fun loadGoals(): JsonArray? {
        val file = File(progressFile)
        if (!file.exists()) return null
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject["goals"]?.jsonArray
        }.onFailure { log.warn("Failed to parse progress.json: {}", it.message) }
            .getOrNull()
    }

    private fun isAllowed(userId: String?): Boolean =
        userId != null && userId in allowedUsers

    @Tool("personalProgress")
    @LLMDescription("개인 성과 목표 전체 진척도를 조회합니다. '올해 성과', '목표 진척도' 같은 질문에 사용하세요.")
    fun getProgressSummary(
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("PersonalData")

        val goals = loadGoals() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."

        return buildString {
            appendLine("📊 2026 성과 목표 현황\n")
            for (goal in goals) {
                val obj = goal.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val weight = obj["weight"]?.jsonPrimitive?.int ?: 0
                appendLine("### $name (가중치 ${weight}%)")

                val indicators = obj["indicators"]?.jsonArray ?: continue
                for (ind in indicators) {
                    val io = ind.jsonObject
                    val indName = io["name"]?.jsonPrimitive?.content ?: ""
                    val current = io["current"]?.jsonPrimitive?.int ?: 0
                    val target = io["target"]?.jsonPrimitive?.int ?: 0
                    val unit = io["unit"]?.jsonPrimitive?.content ?: ""
                    val completed = io["completed"]?.jsonPrimitive?.booleanOrNull ?: false
                    val pct = if (target > 0) (current * 100 / target) else 0
                    val status = if (completed) "✅" else if (pct >= 100) "✅" else "🔄"
                    appendLine("- $status $indName: $current/$target $unit ($pct%)")
                }
                appendLine()
            }
        }.trimEnd()
    }

    @Tool("personalGoalQuery")
    @LLMDescription("특정 성과 목표를 키워드로 검색합니다. 'AI 목표', 'Google 진척도' 같은 질문에 사용하세요.")
    fun queryGoal(
        @LLMDescription("검색할 키워드 (목표명, 지표명, 또는 keywords에 포함된 단어)")
        keyword: String,
        @LLMDescription("요청한 사용자의 Slack userId")
        userId: String,
    ): String {
        if (!isAllowed(userId)) return "이 기능은 허용된 사용자만 사용할 수 있습니다."
        tracker?.record("PersonalData")

        val goals = loadGoals() ?: return "성과 목표 파일이 설정되지 않았거나 읽을 수 없습니다."
        val kw = keyword.lowercase()

        val matched = goals.filter { goal ->
            val obj = goal.jsonObject
            val name = (obj["name"]?.jsonPrimitive?.content ?: "").lowercase()
            val keywords = obj["keywords"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() } ?: emptyList()
            val indicators = obj["indicators"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.lowercase() } ?: emptyList()
            name.contains(kw) || keywords.any { it.contains(kw) } || indicators.any { it.contains(kw) }
        }

        if (matched.isEmpty()) return "'$keyword' 관련 성과 목표를 찾을 수 없습니다."

        return buildString {
            for (goal in matched) {
                val obj = goal.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val weight = obj["weight"]?.jsonPrimitive?.int ?: 0
                val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                appendLine("### $name (가중치 ${weight}%)")
                if (desc.isNotBlank()) appendLine("> $desc\n")

                val indicators = obj["indicators"]?.jsonArray ?: continue
                for (ind in indicators) {
                    val io = ind.jsonObject
                    val indName = io["name"]?.jsonPrimitive?.content ?: ""
                    val current = io["current"]?.jsonPrimitive?.int ?: 0
                    val target = io["target"]?.jsonPrimitive?.int ?: 0
                    val unit = io["unit"]?.jsonPrimitive?.content ?: ""
                    val details = io["details"]?.jsonPrimitive?.content ?: ""
                    val completed = io["completed"]?.jsonPrimitive?.booleanOrNull ?: false
                    val pct = if (target > 0) (current * 100 / target) else 0
                    val status = if (completed) "✅" else "🔄"
                    appendLine("- $status **$indName**: $current/$target $unit ($pct%)")
                    if (details.isNotBlank()) appendLine("  $details")
                }
                appendLine()
            }
        }.trimEnd()
    }
}
