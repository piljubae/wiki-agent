package io.github.veronikapj.wiki.agent

interface SearchProgressListener {
    suspend fun onSearchStarted(toolName: String)
    suspend fun onSearchCompleted(toolName: String)
}
