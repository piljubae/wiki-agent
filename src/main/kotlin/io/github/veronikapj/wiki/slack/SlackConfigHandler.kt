package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.WikiConfig
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onIngest: (suspend (String) -> String)? = null,
    private val onIngestWiki: (() -> String)? = null,
    private val onLint: (suspend () -> String)? = null,
    private val onReindexCode: (suspend () -> Int)? = null,
    private val onReindexPr: (suspend () -> Int)? = null,
    private val projectMemory: ProjectMemory? = null,
    private val onGetIndexCount: (suspend () -> Int)? = null,
    /** restart/stop 실행 권한을 가진 Slack user ID 집합. 비어 있으면 누구도 실행 불가. */
    private val adminUsers: Set<String> = emptySet(),
    /** supervisor(run.sh) 하에서 실행 중인지. false면 restart해도 봇이 다시 살아나지 못한다. */
    private val supervised: Boolean = false,
    /** 재시작 트리거 — marker 기록 후 프로세스 종료. supervisor가 감지해 재실행. */
    private val onRestart: (() -> Unit)? = null,
    /** 종료 트리거 — 프로세스 종료(재실행 없음). */
    private val onStop: (() -> Unit)? = null,
) {
    fun currentConfig(): WikiConfig = config

    fun handle(command: String, userId: String? = null): String {
        val parts = command.trim().split(" ")
        return when {
            parts.size >= 2 && parts[1] == "help" -> helpMessage()
            parts.size >= 2 && parts[1] == "memory" -> handleMemory(parts.drop(2))
            parts.size >= 2 && parts[1] == "ingest-wiki" -> onIngestWiki?.invoke() ?: "Knowledge Base가 비활성화 상태입니다."
            parts.size >= 3 && parts[1] == "ingest" -> handleIngest(parts.drop(2).joinToString(" "))
            parts.size >= 2 && parts[1] == "lint" -> handleLint()
            parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
                val arg = parts.getOrNull(3)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(parts.drop(3).joinToString(" "))
            }
            parts.size >= 2 && parts[1] == "reindex-code" ->
                triggerReindexCode()
            parts.size >= 2 && parts[1] == "reindex-pr" ->
                triggerReindexPr()
            parts.size >= 2 && parts[1] == "restart" -> handleRestart(userId)
            parts.size >= 2 && parts[1] == "stop" -> handleStop(userId)
            else -> helpMessage()
        }
    }

    private fun requireAdmin(userId: String?): String? {
        if (adminUsers.isEmpty())
            return "관리자가 설정되지 않았습니다. config의 `personalData.allowedUsers` 또는 `WIKI_ADMIN_USERS` 환경변수(쉼표 구분 Slack user ID)를 설정하세요."
        if (userId == null || userId !in adminUsers)
            return ":no_entry: 권한이 없습니다. 관리자만 실행할 수 있습니다."
        return null
    }

    private fun handleRestart(userId: String?): String {
        requireAdmin(userId)?.let { return it }
        if (!supervised)
            return ":warning: supervisor 없이 실행 중입니다. 지금 재시작하면 봇이 꺼진 채로 남습니다. `./run.sh`로 실행한 뒤 사용하세요."
        val fn = onRestart ?: return "재시작 기능이 비활성화 상태입니다."
        log.warn("Server restart requested by userId={}", userId)
        fn()
        return ":arrows_counterclockwise: 서버를 재시작합니다. 잠시 후 다시 응답할 수 있습니다…"
    }

    private fun handleStop(userId: String?): String {
        requireAdmin(userId)?.let { return it }
        val fn = onStop ?: return "종료 기능이 비활성화 상태입니다."
        log.warn("Server stop requested by userId={}", userId)
        fn()
        return ":octagonal_sign: 서버를 종료합니다. 다시 켜려면 서버 호스트에서 `./run.sh`를 실행하세요."
    }

    private fun triggerReindexCode(): String {
        val indexer = onReindexCode
            ?: return "코드 인덱싱이 비활성화 상태입니다. config.yml에서 codeRepos를 설정하세요."
        Thread {
            runCatching {
                val count = runBlocking { indexer() }
                log.info("Code reindex completed: {} entries", count)
            }.onFailure { e ->
                log.error("Code reindex failed", e)
            }
        }.start()
        return ":hourglass_flowing_sand: 코드 인덱싱을 시작했습니다."
    }

    private fun triggerReindexPr(): String {
        val indexer = onReindexPr
            ?: return "PR 인덱싱이 비활성화 상태입니다. config.yml에서 codeRepos를 설정하세요."
        Thread {
            runCatching {
                val count = runBlocking { indexer() }
                log.info("PR reindex completed: {} PRs", count)
            }.onFailure { e ->
                log.error("PR reindex failed", e)
            }
        }.start()
        return ":hourglass_flowing_sand: PR 인덱싱(최근 1000건)을 시작했습니다. 40~60분 소요 예상."
    }

    private fun setSpaces(spacesArg: String): String {
        val newSpaces = spacesArg.split(",").map { it.trim() }.filter { it.isNotBlank() }
        config = config.copy(confluence = config.confluence.copy(spaces = newSpaces))
        if (persistOnChange) ConfigLoader.save(config, configPath)
        log.info("Confluence spaces updated: {}", newSpaces)
        return "검색 범위 업데이트: ${newSpaces.joinToString(", ")}"
    }

    private fun showSpaces(): String {
        val spaces = config.confluence.spaces
        return if (spaces.isEmpty()) "현재 설정된 스페이스가 없습니다."
        else "현재 검색 스페이스: ${spaces.joinToString(", ")}"
    }

    private fun handleIngest(url: String): String {
        val fn = onIngest ?: return "Knowledge Base가 비활성화 상태입니다."
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return "사용법: /askpj ingest <URL>\n예: /askpj ingest https://example.com/doc"
        }
        return runBlocking { fn(url) }
    }

    private fun handleLint(): String {
        val fn = onLint ?: return "Knowledge Base가 비활성화 상태입니다."
        return runBlocking { fn() }
    }

    private fun handleMemory(args: List<String>): String {
        val mem = projectMemory ?: return "프로젝트 메모리가 비활성화 상태입니다."
        return when (args.firstOrNull()) {
            "add" -> {
                val content = args.drop(1).joinToString(" ").trim()
                if (content.isBlank()) return "사용법: /askpj memory add <내용>"
                mem.add(content)
                "메모리 저장 완료: $content"
            }
            "show" -> mem.show()
            "clear" -> {
                mem.clear()
                "프로젝트 메모리 초기화 완료"
            }
            else -> "사용법: /askpj memory add|show|clear"
        }
    }

    fun helpMessage() = """
        *배필주2 사용법*

        :mag: *검색*
        • `@배필주2 <질문>` — 채널에서 멘션으로 검색
        • `/askpj <질문>` — 슬래시 커맨드로 검색

        :gear: *설정*
        • `/askpj config space DEV,PM` — 검색 스페이스 설정
        • `/askpj config space show` — 현재 설정 확인
        • `/askpj reindex-code` — Android 소스코드 재인덱싱
        • `/askpj reindex-pr` — PR 히스토리 인덱싱 (최근 1000건, 40~60분)

        :lock: *서버 제어 (관리자 전용)*
        • `/askpj restart` — 서버 재시작 (supervisor 필요)
        • `/askpj stop` — 서버 종료

        :books: *지식베이스*
        • `/askpj ingest <URL>` — URL 내용을 지식베이스에 저장
        • `/askpj ingest-wiki` — docs/wiki/ 문서 전체를 지식베이스에 로드
        • `/askpj lint` — 지식베이스 품질 검사 (모순·고아 감지)

        :brain: *프로젝트 메모리*
        • `/askpj memory add <내용>` — 프로젝트 정보 저장 (도메인 용어, 팀 정보 등)
        • `/askpj memory show` — 저장된 프로젝트 정보 확인
        • `/askpj memory clear` — 프로젝트 정보 초기화

        :bulb: *도움말*
        • `@배필주2 도움말` 또는 `/askpj help`
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
