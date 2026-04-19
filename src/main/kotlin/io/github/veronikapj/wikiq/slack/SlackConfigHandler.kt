package io.github.veronikapj.wikiq.slack

import io.github.veronikapj.wikiq.config.ConfigLoader
import io.github.veronikapj.wikiq.config.WikiqConfig
import org.slf4j.LoggerFactory

class SlackConfigHandler(
    private var config: WikiqConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
) {
    fun currentConfig(): WikiqConfig = config

    fun handle(command: String): String {
        val parts = command.trim().split(" ")
        // parts[0]="/wikiq", parts[1]="config", parts[2]="space", parts[3]=arg
        return when {
            parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
                val arg = parts.getOrNull(3)
                if (arg == "show" || arg == null) showSpaces()
                else setSpaces(parts.drop(3).joinToString(" "))
            }
            else -> helpMessage()
        }
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
        • `/wikiq <질문>` — Confluence에서 검색
        • `/wikiq config space DEV,PM,HR` — 검색 스페이스 설정
        • `/wikiq config space show` — 현재 설정 확인
    """.trimIndent()

    companion object {
        private val log = LoggerFactory.getLogger(SlackConfigHandler::class.java)
    }
}
