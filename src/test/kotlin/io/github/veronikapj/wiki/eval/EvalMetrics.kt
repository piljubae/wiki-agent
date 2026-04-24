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
                normalizedResult.contains(expected) || expected.contains(normalizedResult)
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
                normalizedResult.contains(expected) || expected.contains(normalizedResult)
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

    private fun String.normalize(): String = trim().replace(Regex("\\s+"), " ").lowercase()
}
