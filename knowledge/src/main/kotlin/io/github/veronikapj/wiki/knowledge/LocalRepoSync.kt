package io.github.veronikapj.wiki.knowledge

import org.slf4j.LoggerFactory
import java.io.File

data class DiffResult(
    val modified: List<String>,   // 추가·수정된 파일 (재인덱싱 대상)
    val deleted: List<String>,    // 삭제된 파일 (청크 삭제 대상)
)

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
     * git ls-files로 tracked .kt 파일 목록 반환 (.gitignore 자동 적용).
     * Test 파일 제외.
     */
    fun allKtFiles(): List<String> {
        val output = runGit("ls-files", "*.kt") ?: return emptyList()
        return output.lines()
            .filter { it.endsWith(".kt") && !it.contains("Test") }
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
            .filter { !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") && !it.contains("kpds-compose") }
    }

    /**
     * sinceCommit 이후 변경된 .kt 파일을 수정/삭제로 분리해 반환.
     * git diff --diff-filter:
     *   A = Added, M = Modified  → modified
     *   D = Deleted               → deleted
     */
    fun diffKtFiles(sinceCommit: String): DiffResult {
        val modified = runGit("diff", "--name-only", "--diff-filter=AM", "$sinceCommit..HEAD", "--", "*.kt")
            ?.lines()
            ?.filter { it.endsWith(".kt") && !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") && !it.contains("kpds-compose") }
            ?: emptyList()

        val deleted = runGit("diff", "--name-only", "--diff-filter=D", "$sinceCommit..HEAD", "--", "*.kt")
            ?.lines()
            ?.filter { it.endsWith(".kt") && !it.contains("/build/") && !it.contains("/generated/") && !it.contains("Test") && !it.contains("kpds-compose") }
            ?: emptyList()

        return DiffResult(modified = modified, deleted = deleted)
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
