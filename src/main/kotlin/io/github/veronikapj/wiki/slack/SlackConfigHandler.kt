package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.config.WikiConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onReindex: (suspend () -> Int)? = null,
) {
    private var lastIndexTime: LocalDateTime? = null
    private var lastIndexCount: Int = 0

    fun currentConfig(): WikiConfig = config

    fun handle(command: String): String {
        val parts = command.trim().split(" ")
        return when {
            parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
                val arg = parts.getOrNull(3)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(parts.drop(3).joinToString(" "))
            }
            parts.size >= 2 && parts[1] == "reindex" && parts.getOrNull(2) == "status" ->
                reindexStatus()
            parts.size >= 2 && parts[1] == "reindex" ->
                triggerReindex()
            else -> helpMessage()
        }
    }

    private fun triggerReindex(): String {
        val indexer = onReindex ?: return "RAG가 비활성화 상태입니다. config.yml에서 rag.enabled=true로 설정하세요."
        return runCatching {
            val count = runBlocking { indexer() }
            lastIndexCount = count
            lastIndexTime = LocalDateTime.now()
            "$count 개 문서 인덱싱 완료"
        }.getOrElse { "인덱싱 실패: ${it.message}" }
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

    private fun helpMessage() = """
        사용법:
        • `/wiki <질문>` — Confluence에서 검색
        • `/wiki config space DEV,PM,HR` — 검색 스페이스 설정
        • `/wiki config space show` — 현재 설정 확인
        • `/wiki reindex` — RAG 재인덱싱
        • `/wiki reindex status` — 마지막 인덱싱 정보
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
