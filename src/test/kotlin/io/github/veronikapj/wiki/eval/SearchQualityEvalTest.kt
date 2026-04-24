package io.github.veronikapj.wiki.eval

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@Tag("eval")
class SearchQualityEvalTest {

    private val goldenCases: List<GoldenCase> by lazy {
        val json = this::class.java.getResourceAsStream("/golden-dataset.json")
            ?.reader()?.readText()
            ?: error("golden-dataset.json not found")
        Json { ignoreUnknownKeys = true }.decodeFromString<List<GoldenCase>>(json)
    }

    @Test
    fun `golden dataset loads successfully`() {
        assertTrue(goldenCases.isNotEmpty(), "Golden dataset should not be empty")
        assertTrue(goldenCases.size >= 6, "Should have at least 6 cases")
    }

    @Test
    fun `golden dataset covers manual categories`() {
        val manualCategories = setOf(
            Category.EXACT_MATCH, Category.SYNONYM_GAP, Category.ABBREVIATION,
            Category.PARTIAL_MATCH, Category.MULTI_DOC, Category.ZERO_EXPECTED,
        )
        val present = goldenCases.map { it.category }.toSet()
        manualCategories.forEach { cat ->
            assertTrue(cat in present, "Missing manual category: $cat")
        }
    }

    @Test
    fun `golden dataset has valid structure`() {
        goldenCases.forEach { case ->
            assertTrue(case.id.isNotBlank(), "Case ID should not be blank")
            assertTrue(case.question.isNotBlank(), "Question should not be blank for ${case.id}")
            if (case.category != Category.ZERO_EXPECTED) {
                assertTrue(case.expectedDocTitles.isNotEmpty(), "Expected docs should not be empty for ${case.id}")
            }
        }
    }

    // Placeholder for actual eval tests that require Confluence connection
    // Run with: ./gradlew evalTest
    // @Test
    // fun `recall at 5`() { ... }
}
