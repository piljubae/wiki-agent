package io.github.veronikapj.wiki.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.slf4j.LoggerFactory

class ClaudeCodeLLMClient(
    private val claudePath: String = DEFAULT_CLAUDE_PATH,
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : LLMClient() {

    override fun llmProvider(): LLMProvider = AnthropicModels.Haiku_4_5.provider

    override fun close() {}

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val hasToolResults = prompt.messages.any { it is Message.Tool.Result }

        val flatPrompt = if (tools.isNotEmpty() && !hasToolResults) {
            buildPromptWithTools(prompt, tools)
        } else {
            flattenPrompt(prompt)
        }

        log.info(">>> prompt (tools={}, hasResults={}): {}", tools.map { it.name }, hasToolResults, flatPrompt.take(500))
        val output = runProcess(flatPrompt, model.id)
        log.info("<<< response ({}chars): {}", output.length, output)

        if (tools.isNotEmpty() && !hasToolResults) {
            val toolCall = parseToolCall(output, tools)
            if (toolCall != null) {
                val (name, argsJson) = toolCall
                log.info("Tool call: {} args={}", name, argsJson)
                return listOf(
                    Message.Tool.Call(
                        id = "call-${System.currentTimeMillis()}",
                        tool = name,
                        content = argsJson,
                        metaInfo = ResponseMetaInfo.create(Clock.System),
                    )
                )
            }
        }

        return listOf(Message.Assistant(output, ResponseMetaInfo.create(Clock.System)))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<ai.koog.prompt.streaming.StreamFrame> = flow {
        throw UnsupportedOperationException("Streaming not supported")
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("Moderation not supported")

    private fun buildPromptWithTools(prompt: Prompt, tools: List<ToolDescriptor>): String {
        val systemContent = prompt.messages.filterIsInstance<Message.System>()
            .firstOrNull()?.content ?: ""
        val userQuestion = prompt.messages.filterIsInstance<Message.User>()
            .lastOrNull()?.content ?: ""
        val toolNames = tools.joinToString(", ") { it.name }

        return buildString {
            appendLine(systemContent)
            appendLine()
            appendLine("=== 사용 가능한 Tool ===")
            tools.forEach { tool ->
                appendLine("• ${tool.name}: ${tool.description}")
            }
            appendLine()
            appendLine("=== 지시사항 ===")
            appendLine("반드시 Tool을 호출해서 검색한 뒤 답변하세요.")
            appendLine("Tool을 호출하려면 아래 형식만 출력하고 다른 내용은 쓰지 마세요:")
            appendLine()
            appendLine("SEARCH: <검색어>")
            appendLine()
            appendLine("(사용 가능한 Tool: $toolNames)")
            appendLine()
            appendLine("=== 질문 ===")
            append(userQuestion)
        }.trimEnd()
    }

    private fun flattenPrompt(prompt: Prompt): String = buildString {
        for (message in prompt.messages) {
            when (message) {
                is Message.System -> appendLine("[System]: ${message.content}")
                is Message.User -> appendLine("[Human]: ${message.content}")
                is Message.Assistant -> appendLine("[Assistant]: ${message.content}")
                is Message.Tool.Call -> appendLine("[Tool Call]: ${message.tool}(${message.content})")
                is Message.Tool.Result -> appendLine("[Tool Result]: ${message.content}")
                else -> appendLine(message.content)
            }
        }
    }.trimEnd()

    // "SEARCH: <query>" 형식 파싱 → 첫 번째 tool에 query 인자로 매핑
    private fun parseToolCall(output: String, tools: List<ToolDescriptor>): Pair<String, String>? {
        val match = SEARCH_REGEX.find(output) ?: return null
        val query = match.groupValues[1].trim().ifBlank { return null }
        val toolName = tools.firstOrNull()?.name ?: return null
        val argsJson = """{"query": "${query.replace("\"", "\\\"")}"}"""
        return toolName to argsJson
    }

    private suspend fun runProcess(prompt: String, modelId: String): String = withContext(Dispatchers.IO) {
        // claude CLI를 순수 텍스트 LLM으로만 사용 — 파일시스템 탐색 툴을 비활성화한다.
        // 비활성화하지 않으면 agent가 cwd(= wiki-agent 작업 트리)를 직접 grep해서
        // 주어진 검색 결과 대신 wiki-agent 자체 코드를 근거로 답해버린다.
        val args = mutableListOf(
            claudePath, "-p", prompt, "--output-format", "text",
            "--disallowedTools", "Read", "Grep", "Glob", "Bash", "Edit", "Write", "WebFetch", "WebSearch",
        )
        if (modelId.isNotBlank()) args += listOf("--model", modelId)

        val process = try {
            ProcessBuilder(args)
                .directory(SANDBOX_DIR)  // 중립 작업 디렉토리 — 설령 탐색해도 wiki-agent 코드가 없는 곳
                .redirectInput(File("/dev/null"))  // subprocess가 stdin 읽지 못하게
                .start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Failed to start claude binary: ${e.message}", e)
        }

        // stderr는 별도로 드레인 (출력에 섞이지 않게)
        val stderrThread = Thread { process.errorStream.bufferedReader().readText() }
        stderrThread.isDaemon = true
        stderrThread.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw TimeoutException("claude CLI timed out after ${timeoutSeconds}s")
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()
        if (exitCode != 0) throw IllegalStateException("claude CLI exited with code $exitCode: ${output.take(300)}")
        output.trim()
    }

    companion object {
        private const val DEFAULT_CLAUDE_PATH = "claude"
        private const val TIMEOUT_SECONDS = 120L
        private val SEARCH_REGEX = Regex("^SEARCH:\\s*(.+)$", RegexOption.MULTILINE)
        private val log = LoggerFactory.getLogger(ClaudeCodeLLMClient::class.java)

        /** claude 서브프로세스를 실행할 중립 작업 디렉토리 (wiki-agent 작업 트리 밖). */
        private val SANDBOX_DIR: File = File(System.getProperty("java.io.tmpdir"))
    }
}
