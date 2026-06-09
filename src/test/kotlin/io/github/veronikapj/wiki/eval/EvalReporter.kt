package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.search.SearchResult
import io.github.veronikapj.wiki.search.SearchStage
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class CaseResult(
    val case: GoldenCase,
    val results: List<SearchResult>,
    val latencyMs: Long,
    val apiCalls: Int,
)

object EvalReporter {

    fun generateReport(caseResults: List<CaseResult>, date: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        val formattedDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        sb.appendLine("=== Search Quality Eval Report ($formattedDate) ===")
        sb.appendLine()

        appendSummary(sb, caseResults)
        appendByCategory(sb, caseResults)
        appendBySearchStage(sb, caseResults)
        appendFailedCases(sb, caseResults)

        return sb.toString().trimEnd()
    }

    private fun appendSummary(sb: StringBuilder, results: List<CaseResult>) {
        val total = results.size
        val recallAt5 = results.count { hitOrHonestZero(it, k = 5) }.toDouble() / total
        val recallAt1 = results.count { hitOrHonestZero(it, k = 1) }.toDouble() / total
        val mrr = results.map { mrr(it) }.average()
        val zeroHitCount = results.count { it.results.isEmpty() }
        val zeroHitRate = zeroHitCount.toDouble() / total
        val zeroExpectedCases = results.filter { it.case.category == Category.ZERO_EXPECTED }
        val honestZeroRate = if (zeroExpectedCases.isNotEmpty()) {
            zeroExpectedCases.count { EvalMetrics.isHonestZero(it.results, it.case.category) }
                .toDouble() / zeroExpectedCases.size
        } else {
            0.0
        }
        val avgLatency = results.map { it.latencyMs }.average()
        val avgApiCalls = results.map { it.apiCalls }.average()

        sb.appendLine("[Summary]")
        sb.appendLine(
            "Total cases: $total | Recall@5: ${pct(recallAt5)} | Recall@1: ${pct(recallAt1)} | MRR: ${"%.2f".format(mrr)}",
        )
        sb.appendLine(
            "Zero-hit: ${pct(zeroHitRate)} | Honest-zero: ${pct(honestZeroRate)}",
        )
        sb.appendLine(
            "Avg latency: %,.0fms | Avg API calls: %.1f/query".format(avgLatency, avgApiCalls),
        )
        sb.appendLine()
    }

    private fun appendByCategory(sb: StringBuilder, results: List<CaseResult>) {
        sb.appendLine("[By Category]")
        sb.appendLine("%-17s| %5s | %5s | %5s | %4s | %6s".format("Category", "Count", "R@5", "R@1", "MRR", "Avg ms"))

        Category.entries.forEach { category ->
            val group = results.filter { it.case.category == category }
            if (group.isEmpty()) return@forEach
            val count = group.size
            val r5 = group.count { hitOrHonestZero(it, k = 5) }.toDouble() / count
            val r1 = group.count { hitOrHonestZero(it, k = 1) }.toDouble() / count
            val mrrVal = group.map { mrr(it) }.average()
            val avgMs = group.map { it.latencyMs }.average()
            sb.appendLine(
                "%-17s| %5d | %5s | %5s | %4s | %6.0f".format(
                    category.name, count, pct(r5), pct(r1), "%.2f".format(mrrVal), avgMs,
                ),
            )
        }
        sb.appendLine()
    }

    private fun appendBySearchStage(sb: StringBuilder, results: List<CaseResult>) {
        sb.appendLine("[By Search Stage (hit source)]")
        sb.appendLine("%-17s| %5s | %s".format("Stage", "Count", "%"))

        // Count the stage of the first hit result for each case that has results
        val stageCounts = mutableMapOf<SearchStage, Int>()
        val casesWithResults = results.filter { it.results.isNotEmpty() }
        casesWithResults.forEach { cr ->
            val firstHitStage = firstMatchingStage(cr)
            if (firstHitStage != null) {
                stageCounts[firstHitStage] = (stageCounts[firstHitStage] ?: 0) + 1
            }
        }

        val totalHits = stageCounts.values.sum()
        if (totalHits == 0) {
            sb.appendLine("(no hits)")
            return
        }
        SearchStage.entries.forEach { stage ->
            val count = stageCounts[stage] ?: 0
            if (count == 0) return@forEach
            val pctVal = count.toDouble() / totalHits * 100.0
            sb.appendLine("%-17s| %5d | %.1f%%".format(stage.name, count, pctVal))
        }
        sb.appendLine()
    }

    private fun appendFailedCases(sb: StringBuilder, results: List<CaseResult>) {
        val failed = results.filter { !hitOrHonestZero(it, k = 5) }
        if (failed.isEmpty()) return

        sb.appendLine("[Failed Cases]")
        sb.appendLine("%-9s| %-22s| %-21s| %s".format("ID", "Question", "Expected", "Got (top 3)"))

        failed.forEach { cr ->
            val question = cr.case.question.take(20).padEnd(20) + if (cr.case.question.length > 20) "\u2026" else " "
            val expected = cr.case.expectedDocTitles.firstOrNull()?.take(19)?.padEnd(19)
                ?.let { it + if ((cr.case.expectedDocTitles.firstOrNull()?.length ?: 0) > 19) "\u2026" else " " }
                ?: "(none)".padEnd(20)
            val got = if (cr.results.isEmpty()) {
                "(none)"
            } else {
                cr.results.take(3).joinToString(", ") { it.title.take(20) }
            }
            sb.appendLine("%-9s| %-22s| %-21s| %s".format(cr.case.id, question, expected, got))
        }
    }

    private fun hitOrHonestZero(cr: CaseResult, k: Int): Boolean {
        if (cr.case.category == Category.ZERO_EXPECTED) {
            return EvalMetrics.isHonestZero(cr.results, cr.case.category)
        }
        return EvalMetrics.hitAtK(cr.results, cr.case.expectedDocTitles, k)
    }

    private fun mrr(cr: CaseResult): Double {
        if (cr.case.category == Category.ZERO_EXPECTED) {
            return if (cr.results.isEmpty()) 1.0 else 0.0
        }
        return EvalMetrics.reciprocalRank(cr.results, cr.case.expectedDocTitles)
    }

    private fun firstMatchingStage(cr: CaseResult): SearchStage? {
        val normalizedExpected = cr.case.expectedDocTitles.map {
            it.trim().replace(Regex("\\s+"), " ").lowercase()
        }
        return cr.results.firstOrNull { result ->
            val normalizedResult = result.title.trim().replace(Regex("\\s+"), " ").lowercase()
            normalizedExpected.any { expected ->
                EvalMetrics.titleMatches(normalizedResult, expected)
            }
        }?.stage
    }

    private fun pct(value: Double): String = "%.1f%%".format(value * 100.0)
}
