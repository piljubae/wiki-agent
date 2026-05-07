package io.github.veronikapj.callgraph

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallGraphDbTest {
    private lateinit var dbFile: File
    private lateinit var db: CallGraphDb

    @BeforeEach fun setup() {
        dbFile = File.createTempFile("test_cg", ".db")
        db = CallGraphDb(dbFile.absolutePath)
    }

    @AfterEach fun teardown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun `upsertEdge and findCallers round-trip`() {
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        db.upsertEdge("com.kurly.HomeViewModel.load", "com.kurly.GetProductUseCase.invoke", "HomeViewModel.kt")

        val callers = db.findCallers("com.kurly.GetProductUseCase.invoke")
        assertEquals(2, callers.size)
        assertTrue(callers.any { it.callerFqn == "com.kurly.ProductViewModel.load" })
    }

    @Test
    fun `findCallees returns direct callees`() {
        db.upsertEdge("com.kurly.GetProductUseCase.invoke", "com.kurly.ProductRepository.get", "GetProductUseCase.kt")
        val callees = db.findCallees("com.kurly.GetProductUseCase.invoke")
        assertEquals(1, callees.size)
        assertEquals("com.kurly.ProductRepository.get", callees.first().calleeFqn)
    }

    @Test
    fun `findCallersLike matches partial FQN`() {
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        val callers = db.findCallersLike("GetProductUseCase")
        assertEquals(1, callers.size)
    }

    @Test
    fun `upsert is idempotent`() {
        db.upsertEdge("A.foo", "B.bar", "A.kt")
        db.upsertEdge("A.foo", "B.bar", "A.kt")
        assertEquals(1, db.findCallers("B.bar").size)
    }
}
