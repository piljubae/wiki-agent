package io.github.veronikapj.wiki.search.tool

import io.github.veronikapj.callgraph.CallGraphDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class CodeFlowToolTest {
    private lateinit var dbFile: File
    private lateinit var db: CallGraphDb
    private lateinit var tool: CodeFlowTool

    @BeforeEach fun setup() {
        dbFile = File.createTempFile("cg_tool", ".db")
        db = CallGraphDb(dbFile.absolutePath)
        // VM → UseCase → Repo → Api
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        db.upsertEdge("com.kurly.GetProductUseCase.invoke", "com.kurly.ProductRepository.get", "GetProductUseCase.kt")
        db.upsertEdge("com.kurly.ProductRepository.get", "com.kurly.ProductApi.fetch", "ProductRepository.kt")
        db.upsertEdge("com.kurly.HomeViewModel.load", "com.kurly.GetProductUseCase.invoke", "HomeViewModel.kt")
        db.close()
        tool = CodeFlowTool(dbFile.absolutePath)
    }

    @AfterEach fun teardown() { dbFile.delete() }

    @Test
    fun `findCallers returns all callers`() {
        val result = tool.findCallers("GetProductUseCase.invoke")
        assertTrue(result.contains("ProductViewModel.load"))
        assertTrue(result.contains("HomeViewModel.load"))
    }

    @Test
    fun `traceChain follows forward call chain`() {
        val result = tool.traceChain("ProductViewModel.load")
        assertTrue(result.contains("GetProductUseCase"))
        assertTrue(result.contains("ProductRepository"))
        assertTrue(result.contains("ProductApi"))
    }

    @Test
    fun `findImpact returns reverse transitive closure`() {
        val result = tool.findImpact("ProductApi.fetch")
        assertTrue(result.contains("ProductRepository"))
        assertTrue(result.contains("GetProductUseCase"))
        assertTrue(result.contains("ProductViewModel"))
        assertTrue(result.contains("HomeViewModel"))
    }

    @Test
    fun `findCallers returns not-found message when no callers`() {
        val result = tool.findCallers("NonExistent.function")
        assertTrue(result.contains("찾지 못했습니다"))
    }
}
