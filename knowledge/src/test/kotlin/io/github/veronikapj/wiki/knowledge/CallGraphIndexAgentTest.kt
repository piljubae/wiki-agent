package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CallGraphIndexAgentTest {

    @Test
    fun `buildGradleCommand includes compileDebugKotlin and build-cache`() {
        val agent = CallGraphIndexAgent(cloneRepoPath = "/tmp/kurly", dbPath = "/tmp/cg.db")
        val cmd = agent.buildGradleCommand()
        assertTrue(cmd.contains("compileDebugKotlin"), "must target compileDebugKotlin")
        assertTrue(cmd.contains("--build-cache"), "must use incremental build cache")
    }
}
