package io.github.veronikapj.wiki.knowledge

import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeIndexAgentTest {

    private val agent = CodeIndexAgent(
        codeClient = mockk(relaxed = true),
        llmFn = { "클래스 요약" },
        chromaClient = mockk(relaxed = true),
        repos = emptyList(),
        branch = "develop",
    )

    @Test
    fun `extractClasses — Kotlin 파일에서 class 선언 추출`() {
        val content = """
            package com.kurly.features.banner

            class BannerViewModel : ViewModel() {
                fun onBannerClick() {}
                private fun loadData() {}
            }

            data class BannerUiState(val items: List<Banner> = emptyList())

            sealed interface BannerEvent {
                data object Click : BannerEvent
            }

            enum class BannerState { LOADING, SUCCESS, ERROR }
        """.trimIndent()

        val classes = agent.extractClasses(content)
        assertEquals(4, classes.size)
        assertTrue(classes.any { it.name == "BannerViewModel" })
        assertTrue(classes.any { it.name == "BannerUiState" })
        assertTrue(classes.any { it.name == "BannerEvent" })
        assertTrue(classes.any { it.name == "BannerState" })
    }

    @Test
    fun `extractClasses — public 함수 시그니처 포함`() {
        val content = """
            class MyClass {
                fun publicFun(a: String): Int = 0
                private fun privateFun() {}
                suspend fun suspendFun(): String = ""
            }
        """.trimIndent()

        val classes = agent.extractClasses(content)
        val myClass = classes.first { it.name == "MyClass" }
        assertTrue(myClass.publicFunctions.contains("publicFun(a: String): Int"))
        assertTrue(myClass.publicFunctions.contains("suspendFun(): String"))
        assertTrue(!myClass.publicFunctions.contains("privateFun"))
    }

    @Test
    fun `buildIndexDocument — package + class + functions 포함된 문서 생성`() {
        val classInfo = CodeIndexAgent.KotlinClassInfo(
            name = "BannerViewModel",
            kind = "class",
            packageName = "com.kurly.features.banner",
            publicFunctions = listOf("onBannerClick(bannerId: String)", "loadBanners()"),
            firstLines = "class BannerViewModel : ViewModel() {",
        )
        val doc = agent.buildIndexDocument("features/BannerViewModel.kt", classInfo)
        assertTrue(doc.contains("BannerViewModel"))
        assertTrue(doc.contains("com.kurly.features.banner"))
        assertTrue(doc.contains("onBannerClick"))
    }

    @Test
    fun `extractFunctionChunks — const val 상수 인덱싱`() {
        val content = """
            package com.kurly.link.extensions

            const val SCHEME_DEEP_LINK = "kurly"
            const val SCHEME_DEEP_LINK_SSO = "kurly-sso"

            object DeepLinkHost {
                const val PRODUCT = "product"
                const val CART = "cart"
            }

            fun buildUri(host: String): String = "${'$'}{SCHEME_DEEP_LINK}://${'$'}host"
        """.trimIndent()

        val chunks = agent.extractFunctionChunks(content, "UriExt.kt")
        val propChunks = chunks.filter { it.chunkType == "property" }
        val propNames = propChunks.map { it.functionName }

        assertTrue(propNames.contains("SCHEME_DEEP_LINK"), "SCHEME_DEEP_LINK must be indexed")
        assertTrue(propNames.contains("SCHEME_DEEP_LINK_SSO"), "SCHEME_DEEP_LINK_SSO must be indexed")
        assertTrue(propNames.contains("PRODUCT"), "PRODUCT const in object must be indexed")
        assertTrue(propNames.contains("CART"), "CART const in object must be indexed")

        val schemeChunk = propChunks.first { it.functionName == "SCHEME_DEEP_LINK" }
        assertTrue(schemeChunk.signature.contains("kurly"), "Signature must include the string value")
        assertEquals("", schemeChunk.className, "Top-level const has empty className")

        val productChunk = propChunks.first { it.functionName == "PRODUCT" }
        assertEquals("DeepLinkHost", productChunk.className, "const inside object has className=DeepLinkHost")
    }

    @Test
    fun `extractFunctionChunks — camelCase val은 인덱싱 안 됨`() {
        val content = """
            package com.example

            val isLoading = false
            val itemCount = 0
            val TOTAL_COUNT = 100
        """.trimIndent()

        val chunks = agent.extractFunctionChunks(content, "Example.kt")
        val propChunks = chunks.filter { it.chunkType == "property" }
        val propNames = propChunks.map { it.functionName }

        assertTrue(!propNames.contains("isLoading"), "camelCase val should NOT be indexed")
        assertTrue(!propNames.contains("itemCount"), "camelCase val should NOT be indexed")
        assertTrue(propNames.contains("TOTAL_COUNT"), "SCREAMING_CASE val should be indexed")
    }

    @Test
    fun `extractFunctionChunks — 함수 0개 파일도 const val 있으면 청크 생성`() {
        val content = """
            package com.kurly.link.generator

            object DeepLinkHost {
                const val PRODUCT = "product"
                const val CART = "cart"
                const val SEARCH = "search"
            }
        """.trimIndent()

        val chunks = agent.extractFunctionChunks(content, "DeepLinkHost.kt")
        assertTrue(chunks.isNotEmpty(), "Files with only const vals must produce chunks")
        assertEquals(3, chunks.count { it.chunkType == "property" })
    }
}
