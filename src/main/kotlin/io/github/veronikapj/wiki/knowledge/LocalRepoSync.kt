package io.github.veronikapj.wiki.knowledge

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 로컬 git repo 동기화 및 변경 파일 추적.
 * localRepoPath 기준으로 git 명령을 실행합니다.
 */
class LocalRepoSync(private val repoPath: String) {

    /** 현재 HEAD commit SHA 반환 */
    fun currentCommit(): String? = runGit("rev-parse", "HEAD")?.trim()

    /** origin/branch로 reset (최신화). 성공 시 새 HEAD SHA 반환 */
    fun sync(branch: String = "develop"): String? {
        runGit("fetch", "origin") ?: return null
        runGit("reset", "--hard", "origin/$branch") ?: return null
        return currentCommit()
    }

    /**
     * sinceCommit 이후 변경된 .kt 파일 경로 목록 반환.
     * Test, build/, generated 경로 제외.
     */
    fun changedKtFiles(sinceCommit: String): List<String> {
        val output = runGit("diff", "--name-only", "$sinceCommit..HEAD", "--", "*.kt")
            ?: return emptyList()
        return output.lines()
            .filter { it.endsWith(".kt") }
            .filter { !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") }
    }

    private fun runGit(vararg args: String): String? {
        return runCatching {
            ProcessBuilder("git", *args)
                .directory(File(repoPath))
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText()
                .takeIf { it.isNotBlank() }
        }.onFailure { log.warn("git {} failed: {}", args.joinToString(" "), it.message) }.getOrNull()
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalRepoSync::class.java)
    }
}
