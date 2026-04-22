package io.github.veronikapj.wiki.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class ProjectMemoryTest {

    private fun createTempMemory(): ProjectMemory {
        val file = File(System.getProperty("java.io.tmpdir"), "wiki-test-memory-${System.nanoTime()}.md")
        return ProjectMemory(file.absolutePath)
    }

    @Test
    fun `load returns null when no file`() {
        val memory = createTempMemory()
        assertNull(memory.load())
    }

    @Test
    fun `add and load returns content`() {
        val memory = createTempMemory()
        memory.add("이 프로젝트는 Spring Boot 3.x 기반")
        memory.add("DB는 PostgreSQL 사용")
        val content = memory.load()
        assertTrue(content!!.contains("Spring Boot 3.x"))
        assertTrue(content.contains("PostgreSQL"))
    }

    @Test
    fun `show returns formatted message`() {
        val memory = createTempMemory()
        memory.add("항목1")
        memory.add("항목2")
        val show = memory.show()
        assertTrue(show.contains("항목1"))
        assertTrue(show.contains("항목2"))
    }

    @Test
    fun `show returns empty message when no memory`() {
        val memory = createTempMemory()
        val show = memory.show()
        assertTrue(show.contains("저장된 메모리가 없습니다"))
    }

    @Test
    fun `clear removes all content`() {
        val memory = createTempMemory()
        memory.add("항목1")
        memory.clear()
        assertNull(memory.load())
    }
}
