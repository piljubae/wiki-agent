package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.callgraph.CallGraphDb
import org.slf4j.LoggerFactory
import java.io.File

class CallGraphIndexAgent(
    val cloneRepoPath: String,
    val dbPath: String,
) {
    fun buildGradleCommand() = listOf(
        "./gradlew", "compileDebugKotlin",
        "--build-cache",
        "--no-daemon",
        "--quiet",
    )

    fun openDb(): CallGraphDb = CallGraphDb(dbPath)

    fun runIndex(): Boolean {
        log.info("CallGraph: incremental build in {}", cloneRepoPath)
        return runCatching {
            val proc = ProcessBuilder(buildGradleCommand())
                .directory(File(cloneRepoPath))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                log.warn("Gradle failed (exit={}): {}", exit, output.takeLast(300))
                false
            } else {
                log.info("CallGraph: build complete, DB at {}", dbPath)
                true
            }
        }.onFailure { log.warn("CallGraph build error: {}", it.message) }.getOrDefault(false)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CallGraphIndexAgent::class.java)
    }
}
