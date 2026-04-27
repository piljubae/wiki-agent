package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.agent.SearchResult

object EvalMetrics {

    /**
     * expectedDocTitles 중 하나라도 상위 K건에 있으면 hit.
     * Title matching is normalized (trimmed, lowercase, whitespace-collapsed)
     * and uses bidirectional contains for partial matching.
     */
    fun hitAtK(results: List<SearchResult>, expectedTitles: List<String>, k: Int): Boolean {
        val topK = results.take(k)
        val normalizedExpected = expectedTitles.map { it.normalize() }
        return topK.any { result ->
            val normalizedResult = result.title.normalize()
            normalizedExpected.any { expected ->
                titleMatches(normalizedResult, expected)
            }
        }
    }

    /**
     * 정답 문서의 reciprocal rank (1-indexed). 없으면 0.0.
     * Returns the reciprocal of the rank of the first matching result.
     */
    fun reciprocalRank(results: List<SearchResult>, expectedTitles: List<String>): Double {
        val normalizedExpected = expectedTitles.map { it.normalize() }
        results.forEachIndexed { index, result ->
            val normalizedResult = result.title.normalize()
            val matches = normalizedExpected.any { expected ->
                titleMatches(normalizedResult, expected)
            }
            if (matches) return 1.0 / (index + 1)
        }
        return 0.0
    }

    /**
     * ZERO_EXPECTED 카테고리에서 결과가 비어있으면 honest zero.
     * Only applies to ZERO_EXPECTED category.
     */
    fun isHonestZero(results: List<SearchResult>, category: Category): Boolean {
        return category == Category.ZERO_EXPECTED && results.isEmpty()
    }

    /**
     * 두 제목이 매치되는지 판단.
     * 1) 연속 substring 매치 (기존)
     * 2) 단어 겹침: 짧은 쪽 단어의 50% 이상이 긴 쪽에 포함되면 match
     */
    internal fun titleMatches(a: String, b: String): Boolean {
        // 1. substring match
        if (a.contains(b) || b.contains(a)) return true

        // 2. word overlap match
        val wordsA = a.split(Regex("\\s+")).filter { it.length >= 2 }.toSet()
        val wordsB = b.split(Regex("\\s+")).filter { it.length >= 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val shorter = if (wordsA.size <= wordsB.size) wordsA else wordsB
        val longer = if (wordsA.size <= wordsB.size) wordsB else wordsA
        val overlap = shorter.count { sw -> longer.any { lw -> lw.contains(sw) || sw.contains(lw) } }
        return overlap.toDouble() / shorter.size >= 0.5
    }

    private fun String.normalize(): String = trim().replace(Regex("\\s+"), " ").lowercase()
}
