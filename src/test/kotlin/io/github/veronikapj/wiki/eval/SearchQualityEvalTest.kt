package io.github.veronikapj.wiki.eval

import io.github.veronikapj.wiki.search.ConfluenceSearchAgent
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
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
    }

    @Test
    fun `golden dataset covers auto-generation categories`() {
        val autoCategories = setOf(Category.TITLE_BASED, Category.LLM_GENERATED, Category.PARAPHRASE)
        val present = goldenCases.map { it.category }.toSet()
        autoCategories.forEach { cat ->
            assertTrue(cat in present, "Missing auto-generation category: $cat")
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

    @Test
    fun `search quality eval — recall and report`() = runBlocking {
        val config = ConfigLoader.load()
        val token = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
        val client = ConfluenceClient(config.confluence.baseUrl, token)
        val agent = ConfluenceSearchAgent(client, config.confluence.spaces)

        val caseResults = goldenCases.map { case ->
            val start = System.currentTimeMillis()
            val results = runCatching {
                agent.searchStructured(case.question)
            }.getOrElse { emptyList() }
            val elapsed = System.currentTimeMillis() - start

            // early return = 1 API call, parallel fallback = 3 calls
            val estimatedApiCalls = if (results.size >= 3 && results.all { it.stage == io.github.veronikapj.wiki.search.SearchStage.TITLE_MATCH }) 1 else 3

            CaseResult(
                case = case,
                results = results,
                latencyMs = elapsed,
                apiCalls = estimatedApiCalls,
            )
        }

        val report = EvalReporter.generateReport(caseResults)
        println(report)

        // 리포트 파일 저장
        val date = java.time.LocalDate.now()
        val dir = File("docs/eval").also { it.mkdirs() }
        File(dir, "$date-eval-report.txt").writeText(report)
        println("\nReport saved to docs/eval/$date-eval-report.txt")

        client.close()
    }
}
