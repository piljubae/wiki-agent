package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.WikiConfig
import io.github.veronikapj.wiki.context.ProjectMemory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onReindex: (suspend () -> Int)? = null,
    private val onIngest: (suspend (String) -> String)? = null,
    private val onIngestWiki: (() -> String)? = null,
    private val onLint: (suspend () -> String)? = null,
    private val onReindexCode: (suspend () -> Int)? = null,
    private val onReindexPr: (suspend () -> Int)? = null,
    private val onReindexPrFull: (suspend () -> Int)? = null,
    private val projectMemory: ProjectMemory? = null,
) {
    private var lastIndexTime: LocalDateTime? = null
    private var lastIndexCount: Int = 0

    fun currentConfig(): WikiConfig = config

    fun handle(command: String): String {
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
            parts.size >= 2 && parts[1] == "reindex-pr" && parts.getOrNull(2) == "full" ->
                triggerReindexPrFull()
            parts.size >= 2 && parts[1] == "reindex-pr" ->
                triggerReindexPr()
            parts.size >= 2 && parts[1] == "reindex" && parts.getOrNull(2) == "status" ->
                reindexStatus()
            parts.size >= 2 && parts[1] == "reindex" ->
                triggerReindex()
            else -> helpMessage()
        }
    }

    private fun triggerReindex(): String {
        val indexer = onReindex ?: return "RAG가 비활성화 상태입니다. config.yml에서 rag.enabled=true로 설정하세요."
        // 비동기 실행 — 슬래시 커맨드 3초 타임아웃 방지
        Thread {
            runCatching {
                val count = runBlocking { indexer() }
                lastIndexCount = count
                lastIndexTime = LocalDateTime.now()
                log.info("Reindex completed: {} pages", count)
            }.onFailure { e ->
                log.error("Reindex failed", e)
            }
        }.start()
        return ":hourglass_flowing_sand: 인덱싱을 시작했습니다. `/askpj reindex status`로 진행 상황을 확인하세요."
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
        return ":hourglass_flowing_sand: PR 인덱싱을 시작했습니다."
    }

    private fun triggerReindexPrFull(): String {
        val indexer = onReindexPrFull
            ?: return "PR 인덱싱이 비활성화 상태입니다. config.yml에서 codeRepos를 설정하세요."
        Thread {
            runCatching {
                val count = runBlocking { indexer() }
                log.info("PR full reindex completed: {} PRs", count)
            }.onFailure { e ->
                log.error("PR full reindex failed", e)
            }
        }.start()
        return ":hourglass_flowing_sand: PR 전체 인덱싱(최근 1000건)을 시작했습니다. 40~60분 소요 예상."
    }

    private fun reindexStatus(): String {
        val time = lastIndexTime?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            ?: "아직 인덱싱하지 않았습니다"
        return "마지막 인덱싱: $time / 문서 수: $lastIndexCount"
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
        • `/askpj reindex` — RAG 재인덱싱
        • `/askpj reindex status` — 마지막 인덱싱 정보
        • `/askpj reindex-code` — Android 소스코드 재인덱싱
        • `/askpj reindex-pr` — PR 히스토리 재인덱싱 (최신 50건)
        • `/askpj reindex-pr full` — PR 전체 인덱싱 (최근 1000건, 40~60분)

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
