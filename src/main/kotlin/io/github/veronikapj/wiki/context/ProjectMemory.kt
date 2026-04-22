package io.github.veronikapj.wiki.context

import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ProjectMemory(private val filePath: String = ".wiki/memory.md") {

    private val lock = ReentrantLock()

    fun load(): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        return file.readText().ifBlank { null }
    }

    fun add(content: String) = lock.withLock {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.appendText("- $content\n")
    }

    fun show(): String {
        val content = load() ?: return "저장된 메모리가 없습니다."
        return "📝 프로젝트 메모리:\n$content"
    }

    fun clear() = lock.withLock {
        val file = File(filePath)
        if (file.exists() && !file.delete()) {
            throw IOException("메모리 파일 삭제 실패: $filePath")
        }
    }
}
