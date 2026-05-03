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
}
