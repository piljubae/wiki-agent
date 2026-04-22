package io.github.veronikapj.wiki.context

import java.io.File

class ProjectMemory(private val filePath: String = ".wiki/memory.md") {

    fun load(): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        return file.readText().ifBlank { null }
    }

    fun add(content: String) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.appendText("- $content\n")
    }

    fun show(): String {
        val content = load() ?: return "저장된 메모리가 없습니다."
        return "📝 프로젝트 메모리:\n$content"
    }

    fun clear() {
        val file = File(filePath)
        if (file.exists()) file.delete()
    }
}
