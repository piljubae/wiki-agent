package io.github.veronikapj.wikiq.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
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
        if (tools.isNotEmpty()) {
            log.warn(
                "ClaudeCodeLLMClient does not support tool calls — {} tool(s) will be ignored.",
                tools.size,
            )
        }
        val flatPrompt = flattenPrompt(prompt)
        val args = buildArgs(flatPrompt, model.id)
        log.debug("Executing claude CLI: {}", args.joinToString(" "))
        val output = runProcess(args)
        return listOf(Message.Assistant(output, ResponseMetaInfo.create(Clock.System)))
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<ai.koog.prompt.streaming.StreamFrame> = flow {
        throw UnsupportedOperationException("ClaudeCodeLLMClient does not support streaming execution")
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("ClaudeCodeLLMClient does not support moderation")

    suspend fun checkAuth() {
        runProcess(listOf(claudePath, "auth", "status"))
    }

    private fun flattenPrompt(prompt: Prompt): String {
        val sb = StringBuilder()
        for (message in prompt.messages) {
            when (message) {
                is Message.System -> sb.appendLine("[System]: ${message.content}")
                is Message.User -> sb.appendLine("[Human]: ${message.content}")
                is Message.Assistant -> sb.appendLine("[Assistant]: ${message.content}")
                else -> sb.appendLine(message.content)
            }
        }
        return sb.toString().trimEnd()
    }

    private fun buildArgs(flatPrompt: String, modelId: String): List<String> {
        val args = mutableListOf(claudePath, "-p", flatPrompt, "--output-format", "text")
        if (modelId.isNotBlank()) args += listOf("--model", modelId)
        return args
    }

    private suspend fun runProcess(args: List<String>): String = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Failed to start claude binary at '${args.first()}': ${e.message}", e)
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw TimeoutException("claude CLI timed out after ${timeoutSeconds}s")
        }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.exitValue()
        if (exitCode != 0) throw IllegalStateException("claude CLI exited with code $exitCode. Output: ${output.take(500)}")
        output.trim()
    }

    companion object {
        private const val DEFAULT_CLAUDE_PATH = "claude"
        private const val TIMEOUT_SECONDS = 120L
        private val log = LoggerFactory.getLogger(ClaudeCodeLLMClient::class.java)
    }
}
