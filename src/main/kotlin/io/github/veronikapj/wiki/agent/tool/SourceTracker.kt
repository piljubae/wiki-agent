package io.github.veronikapj.wiki.agent.tool

class SourceTracker {
    private val _sources = mutableListOf<String>()
    val sources: List<String> get() = _sources.toList()

    fun reset() = _sources.clear()
    fun record(source: String) { if (source !in _sources) _sources.add(source) }
}
