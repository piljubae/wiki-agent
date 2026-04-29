package io.github.veronikapj.wiki.eval

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Router-inclusive eval: LLM이 QUERY/SYNONYMS를 추출한 뒤 searchStructured를 호출합니다.
 * SYNONYM_GAP, ABBREVIATION 등 router 확장이 필요한 케이스를 평가합니다.
 */
@Tag("eval")
class RouterEvalTest {

    // Router step이 핵심적으로 필요한 카테고리만 — LLM_GENERATED/PARAPHRASE는 SearchQualityEvalTest에서 커버
    private val routerCategories = setOf(
        Category.SYNONYM_GAP,
        Category.ABBREVIATION,
    )

    private val goldenCases: List<GoldenCase> by lazy {
        val json = this::class.java.getResourceAsStream("/golden-dataset.json")
            ?.reader()?.readText()
            ?: error("golden-dataset.json not found")
        Json { ignoreUnknownKeys = true }.decodeFromString<List<GoldenCase>>(json)
            .filter { it.category in routerCategories }
    }

    @Test
    fun `router eval — recall with LLM query expansion`() = runBlocking {
        val config = ConfigLoader.load()
        val token = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
        val client = ConfluenceClient(config.confluence.baseUrl, token)
        val agent = ConfluenceSearchAgent(client, config.confluence.spaces)
        val executor = LLMExecutorBuilder.build(config.model)
        val model = AnthropicModels.Haiku_4_5

        val routerPrompt = buildRouterPrompt()

        val caseResults = goldenCases.map { case ->
            val start = System.currentTimeMillis()

            // 1단계: 라우터 LLM → QUERY + SYNONYMS 추출
            val fullPrompt = "$routerPrompt\n질문: ${case.question}"
            val decision = runCatching {
                executor.execute(prompt("router") { user(fullPrompt) }, model)
                    .joinToString("") { it.content }.trim()
            }.getOrElse { "" }

            val query = Regex("QUERY:\\s*(.+)").find(decision)?.groupValues?.get(1)?.trim()
                ?: case.question
            val synonyms = Regex("SYNONYMS:\\s*(.+)").find(decision)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            val dateAfter = Regex("DATE_AFTER:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()
            val dateBefore = Regex("DATE_BEFORE:\\s*(\\S+)").find(decision)?.groupValues?.get(1)?.trim()

            println("[${case.id}] Q: ${case.question}")
            println("  → QUERY: $query | SYNONYMS: $synonyms | dateAfter: $dateAfter | dateBefore: $dateBefore")

            // 2단계: 확장된 query + synonyms + 날짜 필터로 검색
            val results = runCatching {
                agent.searchStructured(query, synonyms, dateAfter = dateAfter, dateBefore = dateBefore)
            }.getOrElse { emptyList() }

            val elapsed = System.currentTimeMillis() - start
            val apiCalls = if (results.size >= 3) 1 else 3

            CaseResult(
                case = case,
                results = results,
                latencyMs = elapsed,
                apiCalls = apiCalls,
            )
        }

        val report = buildString {
            appendLine("=== Router Eval Report (${java.time.LocalDate.now()}) ===")
            appendLine("Scope: ${routerCategories.joinToString(", ") { it.name }} cases only")
            appendLine()
            append(EvalReporter.generateReport(caseResults))
        }

        println(report)

        val date = java.time.LocalDate.now()
        val dir = File("docs/eval").also { it.mkdirs() }
        File(dir, "$date-router-eval-report.txt").writeText(report)
        println("\nReport saved to docs/eval/$date-router-eval-report.txt")

        client.close()
    }

    private fun buildRouterPrompt(): String = buildString {
        appendLine("당신은 검색 라우터입니다. 사용자의 질문 의도를 파악해 Confluence 검색에 최적화된 검색어를 생성합니다.")
        appendLine()
        appendLine("출력 형식 (필수 2줄 + 선택 2줄, 다른 텍스트 금지):")
        appendLine("QUERY: <핵심 검색어>")
        appendLine("SYNONYMS: <확장 검색어 3-6개, 쉼표 구분>")
        appendLine("DATE_AFTER: <YYYY-MM-DD>  ← 최신 문서 의도일 때만 출력")
        appendLine("DATE_BEFORE: <YYYY-MM-DD>  ← 범위 종료일이 있을 때만 출력")
        appendLine()
        appendLine("QUERY 작성 원칙:")
        appendLine("- Confluence 페이지 제목에 들어갈 법한 핵심 용어로 추출하세요.")
        appendLine("- 플랫폼(안드로이드/iOS), 팀명 등 수식어보다 문서 이름 자체를 우선하세요.")
        appendLine("- 예: \"안드로이드 tech talk 위키 찾아줘\" → QUERY: tech talk")
        appendLine("- 예: \"iOS 배포 프로세스 어떻게 돼?\" → QUERY: 배포 프로세스")
        appendLine()
        appendLine("SYNONYMS 작성 원칙 — 아래 유형을 조합해 3-6개 생성 (각 항목이 CQL OR 절로 검색됨):")
        appendLine("1. 수식어 포함 버전: 플랫폼·컨텍스트 붙인 원래 표현 (예: 안드로이드 tech talk)")
        appendLine("2. 단축/핵심 버전: 수식어 뺀 핵심 단어 (예: Tech Talk Talk, 테크톡)")
        appendLine("3. 의미 동의어: 같은 개념의 다른 표현 (예: 기술 공유, 기술 세션)")
        appendLine("4. 영문 변환: 한국어 용어를 영어로도 포함 — 문서 제목이 영문일 수 있음 (예: 아키 TF → Architecture TF Weekly, 온보딩 → Onboarding Guide)")
        appendLine("5. 약어 확장: 약어가 있으면 전체 표현도 포함 (예: PR → Pull Request, TF → 태스크포스)")
        appendLine("6. 날짜 포맷 변환: 날짜가 있으면 여러 포맷으로 추가 — 각 포맷이 제목에 OR 매칭됨 (예: 4월 24일 → 2026/04/24, 04/24, 4/24)")
        appendLine()
        appendLine("DATE_AFTER/DATE_BEFORE 사용 규칙:")
        appendLine("- 특정 날짜 문서 (예: \"4월 24일 미팅 내용\"): DATE_* 미사용, 대신 날짜 포맷을 SYNONYMS에 포함")
        appendLine("- 최신/최근 의도 (예: \"최근 변경된\", \"지난주 업데이트된\"): DATE_AFTER 사용")
        appendLine("- 기간 범위 (예: \"3월~4월 사이\"): DATE_AFTER + DATE_BEFORE 모두 사용")
    }
}
