@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki.eval

import ai.koog.prompt.dsl.prompt
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File

@Tag("generate")
class GoldenDatasetGenerator {

    private val log = LoggerFactory.getLogger(GoldenDatasetGenerator::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `generate golden dataset from Confluence pages`() = runBlocking {
        // 1. Load config + create clients
        val config = ConfigLoader.load()
        val confluenceToken = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
        require(confluenceToken.isNotBlank()) { "CONFLUENCE_TOKEN required" }

        val resolvedModelApiKey = when (config.model.provider) {
            io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
                SecretLoader.resolveNullable("ANTHROPIC_API_KEY", config.model.apiKey)
            io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
                SecretLoader.resolveNullable("GOOGLE_API_KEY", config.model.apiKey)
            else -> config.model.apiKey
        }
        val resolvedModelConfig = config.model.copy(apiKey = resolvedModelApiKey)

        val confluenceClient = ConfluenceClient(
            baseUrl = config.confluence.baseUrl,
            token = confluenceToken,
        )
        val executor = LLMExecutorBuilder.build(resolvedModelConfig)
        val model = LLMExecutorBuilder.defaultModel(resolvedModelConfig)

        // 2. Fetch pages from configured spaces
        val spaces = config.confluence.spaces
        log.info("Fetching pages from spaces: {}", spaces)
        val pages = confluenceClient.listPages(spaces, limit = 50)
        log.info("Fetched {} pages", pages.size)

        // Filter pages with non-blank title and excerpt
        val validPages = pages.filter { it.title.isNotBlank() && it.excerpt.isNotBlank() }
        log.info("Valid pages (non-blank title+excerpt): {}", validPages.size)

        // 3. Load existing golden dataset
        val datasetFile = File("src/test/resources/golden-dataset.json")
        val existing: List<GoldenCase> = if (datasetFile.exists()) {
            json.decodeFromString(datasetFile.readText())
        } else {
            emptyList()
        }

        // Determine next AUTO ID
        val maxAutoId = existing
            .filter { it.id.startsWith("AUTO-") }
            .mapNotNull { it.id.removePrefix("AUTO-").toIntOrNull() }
            .maxOrNull() ?: 0
        var nextId = maxAutoId + 1

        // Track existing sourcePageId+category combos to avoid duplicates
        val existingKeys = existing.map { "${it.sourcePageId}:${it.category}" }.toSet()

        val newCases = mutableListOf<GoldenCase>()

        for (page in validPages) {
            // TITLE_BASED
            if ("${page.id}:${Category.TITLE_BASED}" !in existingKeys) {
                newCases.add(
                    GoldenCase(
                        id = "AUTO-%03d".format(nextId++),
                        question = "${page.title} 알려줘",
                        category = Category.TITLE_BASED,
                        expectedDocTitles = listOf(page.title),
                        sourcePageId = page.id,
                    )
                )
            }

            // LLM_GENERATED
            if ("${page.id}:${Category.LLM_GENERATED}" !in existingKeys) {
                val llmQuestion = generateLlmQuestion(executor, model, page.title, page.excerpt)
                if (llmQuestion.isNotBlank()) {
                    newCases.add(
                        GoldenCase(
                            id = "AUTO-%03d".format(nextId++),
                            question = llmQuestion,
                            category = Category.LLM_GENERATED,
                            expectedDocTitles = listOf(page.title),
                            sourcePageId = page.id,
                        )
                    )
                }
            }

            // PARAPHRASE
            if ("${page.id}:${Category.PARAPHRASE}" !in existingKeys) {
                val paraphraseQuestion = generateParaphraseQuestion(executor, model, page.title, page.excerpt)
                if (paraphraseQuestion.isNotBlank()) {
                    newCases.add(
                        GoldenCase(
                            id = "AUTO-%03d".format(nextId++),
                            question = paraphraseQuestion,
                            category = Category.PARAPHRASE,
                            expectedDocTitles = listOf(page.title),
                            sourcePageId = page.id,
                        )
                    )
                }
            }
        }

        log.info("Generated {} new cases", newCases.size)

        // 4. Merge and write back
        val merged = existing + newCases
        datasetFile.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GoldenCase.serializer()), merged))
        log.info("Written {} total cases to {}", merged.size, datasetFile.path)

        confluenceClient.close()
    }

    private suspend fun generateLlmQuestion(
        executor: ai.koog.prompt.executor.llms.MultiLLMPromptExecutor,
        model: ai.koog.prompt.llm.LLModel,
        title: String,
        excerpt: String,
    ): String {
        val text = """
            다음 문서 제목과 요약을 보고, 이 문서를 찾으려는 사람이 실제로 물어볼 법한 자연스러운 질문을 1개만 생성하세요.
            제목: $title
            요약: ${excerpt.take(200)}

            질문만 출력하세요. 다른 텍스트 없이.
        """.trimIndent()
        return runCatching {
            executor.execute(prompt("llm-question") { user(text) }, model)
                .joinToString("") { it.content }
                .trim()
        }.getOrElse { e ->
            log.warn("LLM question generation failed for '{}': {}", title, e.message)
            ""
        }
    }

    private suspend fun generateParaphraseQuestion(
        executor: ai.koog.prompt.executor.llms.MultiLLMPromptExecutor,
        model: ai.koog.prompt.llm.LLModel,
        title: String,
        excerpt: String,
    ): String {
        val text = """
            다음 문서 제목을 보고, 정확한 용어를 기억 못하는 사람이 검색할 법한 질문을 1개만 생성하세요.
            핵심 단어를 동의어, 유사 표현, 줄임말 등으로 바꾸세요.
            예시: "배포 프로세스 가이드" → "릴리즈 절차가 어떻게 돼?"

            제목: $title
            요약: ${excerpt.take(200)}

            질문만 출력하세요.
        """.trimIndent()
        return runCatching {
            executor.execute(prompt("paraphrase-question") { user(text) }, model)
                .joinToString("") { it.content }
                .trim()
        }.getOrElse { e ->
            log.warn("Paraphrase generation failed for '{}': {}", title, e.message)
            ""
        }
    }
}
