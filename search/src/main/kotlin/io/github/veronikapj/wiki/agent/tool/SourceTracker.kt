package io.github.veronikapj.wiki.agent.tool

class SourceTracker {
    private val _counts = mutableMapOf<String, Int>()
    val sources: List<String> get() = _counts.keys.toList()

    fun reset() = _counts.clear()

    fun record(source: String) {
        _counts[source] = (_counts[source] ?: 0) + 1
    }

    fun countOf(source: String): Int = _counts[source] ?: 0

    fun formatFooter(): String {
        if (_counts.isEmpty()) return ""
        return "📋 " + _counts.entries.joinToString(" · ") { "${it.key} ${it.value}건" }
    }
}
