package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 코드 인덱싱 + BM25 하이브리드 검색 통합 테스트.
 *
 * kurly-android 로컬 체크아웃에서 소수 파일을 직접 읽어 검증합니다.
 * ChromaDB 없이 BM25 단독 동작을 확인합니다.
 *
 * 실행: ./gradlew test --tests "*.CodeIndexIntegrationTest"
 */
@Tag("integration")
class CodeIndexIntegrationTest {

    private val kurlyAndroidPath = "/Users/pilju.bae/AndroidStudioProjects/kurly-android"
    private val bm25DbPath = ".wiki/test-bm25.db"
    private lateinit var bm25Index: BM25Index

    @BeforeEach
    fun setUp() {
        File(bm25DbPath).delete()
        bm25Index = BM25Index(bm25DbPath)
    }

    @AfterEach
    fun tearDown() {
        bm25Index.close()
        File(bm25DbPath).delete()
    }

    // ── Step 2: 함수 추출 테스트 ──────────────────────────────────────────────

    @Test
    fun `extractFunctionChunks - kurly-android 실제 파일에서 함수 추출`() {
        val root = File(kurlyAndroidPath)
        if (!root.exists()) {
            println("SKIP: kurly-android not found at $kurlyAndroidPath")
            return
        }

        // ViewModel 파일 몇 개만 샘플
        val sampleFiles = root.walk()
            .filter { it.extension == "kt" && it.name.contains("ViewModel") && !it.name.contains("Test") }
            .take(5)
            .toList()

        println("\n=== 함수 추출 테스트 (${sampleFiles.size}개 파일) ===")

        val agent = makeAgent()
        var totalChunks = 0

        sampleFiles.forEach { file ->
            val content = file.readText()
            val relativePath = file.relativeTo(root).path
            val chunks = agent.extractFunctionChunks(content, relativePath)
            totalChunks += chunks.size

            println("\n📄 $relativePath")
            println("   함수 ${chunks.size}개 추출:")
            chunks.take(5).forEach { chunk ->
                println("   • [${chunk.className}] ${chunk.signature.take(80)}")
                if (chunk.body.isNotBlank()) {
                    println("     body(${chunk.body.length}자): ${chunk.body.lines().first().take(60)}")
                }
            }
            if (chunks.size > 5) println("   ... 외 ${chunks.size - 5}개")
        }

        println("\n총 함수 청크: $totalChunks")
        assertTrue(totalChunks > 0, "함수 청크가 추출되어야 합니다")
    }

    // ── Step 3: BM25 인덱싱 + 검색 테스트 ───────────────────────────────────

    @Test
    fun `BM25 - 소수 파일 인덱싱 후 키워드 검색`() {
        val root = File(kurlyAndroidPath)
        if (!root.exists()) {
            println("SKIP: kurly-android not found at $kurlyAndroidPath")
            return
        }

        val agent = makeAgent()

        // 20개 파일만 인덱싱
        val sampleFiles = root.walk()
            .filter {
                it.extension == "kt"
                    && !it.path.contains("/build/")
                    && !it.path.contains("/generated/")
                    && !it.name.contains("Test")
            }
            .take(20)
            .toList()

        println("\n=== BM25 인덱싱 (${sampleFiles.size}개 파일) ===")

        var totalChunks = 0
        sampleFiles.forEach { file ->
            val content = file.readText()
            val relativePath = file.relativeTo(root).path
            val chunks = agent.extractFunctionChunks(content, relativePath)
            chunks.forEach { chunk ->
                val id = "kurly-android:$relativePath:${chunk.className}:${chunk.functionName}"
                val doc = agent.buildChunkDocument(chunk)
                bm25Index.upsert(id, doc)
                totalChunks++
            }
        }
        println("인덱싱 완료: $totalChunks 함수 청크\n")

        // 검색 테스트
        val queries = listOf(
            "ViewModel",
            "onClick",
            "StateFlow",
            "repository",
        )

        queries.forEach { query ->
            val results = bm25Index.search(query, limit = 3)
            println("🔍 \"$query\" → ${results.size}건")
            results.forEach { id ->
                // id 형식: repo:path:class:function
                val parts = id.split(":")
                val fn = parts.getOrNull(3) ?: ""
                val cls = parts.getOrNull(2) ?: ""
                println("   • $cls.$fn()")
            }
            println()
        }

        assertTrue(totalChunks > 0)
    }

    @Test
    fun `RRF 병합 - 벡터와 BM25 결과 합산`() {
        val vectorIds = listOf("repo:A.kt:ClassA:funA", "repo:B.kt:ClassB:funB", "repo:C.kt:ClassC:funC")
        val bm25Ids  = listOf("repo:D.kt:ClassD:funD", "repo:A.kt:ClassA:funA", "repo:E.kt:ClassE:funE")

        val merged = BM25Index.mergeRRF(vectorIds, bm25Ids)

        println("\n=== RRF 병합 결과 ===")
        merged.forEachIndexed { i, id -> println("${i + 1}. $id") }

        // 두 결과 모두에 있는 ClassA:funA 가 1위여야 함
        assertTrue(merged.first().contains("ClassA:funA"), "두 검색 모두 hit한 결과가 1위여야 합니다")
        println("\n✅ ClassA:funA (벡터 1위 + BM25 2위) → RRF 1위")
    }

    @Test
    fun `extractFunctionChunks - 멀티라인 파라미터 처리`() {
        val content = """
            package com.example

            class MyViewModel {
                fun createClickFusionSignal(
                    clickType: FusionSignalType,
                    product: ProductModel,
                    position: Int
                ): FusionSignal? {
                    return null
                }

                fun singleLine(id: String): String {
                    return id
                }
            }
        """.trimIndent()

        val agent = makeAgent()
        val chunks = agent.extractFunctionChunks(content, "test/MyViewModel.kt")

        println("\n=== 멀티라인 파라미터 테스트 ===")
        chunks.forEach { c ->
            println("• [${c.className}] ${c.signature}")
        }

        assertTrue(chunks.any { it.functionName == "createClickFusionSignal" },
            "멀티라인 파라미터 함수가 추출되어야 합니다")
        val multiline = chunks.first { it.functionName == "createClickFusionSignal" }
        assertTrue(multiline.signature.contains("clickType"), "파라미터가 시그니처에 포함되어야 합니다")
        assertTrue(multiline.className == "MyViewModel", "className이 MyViewModel이어야 합니다")
        assertTrue(multiline.signature.contains("FusionSignal"), "반환 타입이 포함되어야 합니다")
        println("✅ 멀티라인 파라미터 정상 추출: ${multiline.signature}")
    }

    @Test
    fun `extractFunctionChunks - companion object 함수 className`() {
        val content = """
            package com.example

            class BannerUtils {
                fun outerFun() {}

                companion object {
                    fun create(): BannerUtils = BannerUtils()
                    fun fromId(id: String): BannerUtils = BannerUtils()
                }
            }
        """.trimIndent()

        val agent = makeAgent()
        val chunks = agent.extractFunctionChunks(content, "test/BannerUtils.kt")

        println("\n=== companion object 테스트 ===")
        chunks.forEach { c -> println("• [${c.className}] ${c.functionName}") }

        val createFn = chunks.firstOrNull { it.functionName == "create" }
        assertTrue(createFn != null, "companion object 함수 create가 추출되어야 합니다")
        println("✅ companion object 함수: className=${createFn?.className}")
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun makeAgent() = CodeIndexAgent(
        codeClient = io.github.veronikapj.wiki.github.GitHubCodeClient(""),
        llmFn = { "" },
        chromaClient = io.github.veronikapj.wiki.rag.ChromaClient("http://localhost:8000"),
        repos = listOf("thefarmersfront/kurly-android"),
        bm25Index = bm25Index,
    )
}
