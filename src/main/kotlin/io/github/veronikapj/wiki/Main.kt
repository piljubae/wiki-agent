@file:OptIn(ai.koog.agents.core.annotation.ExperimentalAgentsApi::class)

package io.github.veronikapj.wiki

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.params.LLMParams
import io.github.veronikapj.wiki.agent.ConfluenceSearchAgent
import io.github.veronikapj.wiki.agent.GitHubWikiSearchAgent
import io.github.veronikapj.wiki.agent.OrchestratorAgent
import io.github.veronikapj.wiki.github.GitHubWikiClient
import io.github.veronikapj.wiki.agent.tool.ConfluenceTool
import io.github.veronikapj.wiki.agent.tool.GitHubWikiTool
import io.github.veronikapj.wiki.agent.tool.SourceTracker
import io.github.veronikapj.wiki.agent.tool.VectorSearchTool
import io.github.veronikapj.wiki.knowledge.IngestAgent
import io.github.veronikapj.wiki.knowledge.KnowledgeStore
import io.github.veronikapj.wiki.knowledge.KnowledgeTool
import io.github.veronikapj.wiki.knowledge.LintAgent
import io.github.veronikapj.wiki.config.ConfigLoader
import io.github.veronikapj.wiki.context.ProjectMemory
import io.github.veronikapj.wiki.config.EmbeddingMode
import io.github.veronikapj.wiki.config.SecretLoader
import io.github.veronikapj.wiki.confluence.ConfluenceClient
import io.github.veronikapj.wiki.context.ConversationStore
import io.github.veronikapj.wiki.llm.LLMExecutorBuilder
import io.github.veronikapj.wiki.rag.ChromaClient
import io.github.veronikapj.wiki.rag.GoogleEmbeddingClient
import io.github.veronikapj.wiki.rag.LlmExpandClient
import io.github.veronikapj.wiki.rag.VectorIndexAgent
import io.github.veronikapj.wiki.rag.VectorSearchAgent
import io.github.veronikapj.wiki.agent.QueryRewriter
import io.github.veronikapj.wiki.slack.SlackBotGateway
import io.github.veronikapj.wiki.slack.SlackConfigHandler
import io.github.veronikapj.wiki.github.GitHubCodeClient
import io.github.veronikapj.wiki.knowledge.BM25Index
import io.github.veronikapj.wiki.knowledge.CodeIndexAgent
import io.github.veronikapj.wiki.knowledge.LocalRepoSync
import io.github.veronikapj.wiki.knowledge.PrIndexAgent
import io.github.veronikapj.wiki.agent.tool.CodeFlowTool
import io.github.veronikapj.wiki.agent.tool.PrHistoryTool
import io.github.veronikapj.wiki.agent.tool.CodeSearchTool
import io.github.veronikapj.wiki.knowledge.CallGraphIndexAgent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.application.call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wiki.Main")

fun main() {
    val config = ConfigLoader.load()
    log.info("Provider: {}, Spaces: {}, RAG: {}", config.model.provider, config.confluence.spaces, config.rag.enabled)

    // 시크릿 로드 (env → .env → config 폴백)
    val confluenceToken = SecretLoader.resolve("CONFLUENCE_TOKEN", config.confluence.token)
    val slackBotToken = SecretLoader.resolve("SLACK_BOT_TOKEN", config.slack.botToken)
    val slackAppToken = SecretLoader.resolve("SLACK_APP_TOKEN", config.slack.appToken)
    val resolvedModelApiKey = when (config.model.provider) {
        io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
            SecretLoader.resolveNullable("ANTHROPIC_API_KEY", config.model.apiKey)
        io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
            SecretLoader.resolveNullable("GOOGLE_API_KEY", config.model.apiKey)
        else -> config.model.apiKey
    }
    val resolvedModelConfig = config.model.copy(apiKey = resolvedModelApiKey)

    val executor = LLMExecutorBuilder.build(resolvedModelConfig)
    val model = LLMExecutorBuilder.defaultModel(resolvedModelConfig)

    // Router executor: routerConfig 있으면 별도 빌드, 없으면 executor 재사용
    var routerModel = AnthropicModels.Haiku_4_5
    val routerExecutor = config.routerConfig?.let { routerCfg ->
        val resolvedRouterApiKey = when (routerCfg.provider) {
            io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
                SecretLoader.resolveNullable("ANTHROPIC_API_KEY", routerCfg.apiKey)
            io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
                SecretLoader.resolveNullable("GOOGLE_API_KEY", routerCfg.apiKey)
            else -> routerCfg.apiKey // CLAUDE_CODE / GEMINI_CODE: no API key env var resolution needed
        }
        val resolvedRouterConfig = routerCfg.copy(apiKey = resolvedRouterApiKey)
        log.info("Router executor: provider={}", resolvedRouterConfig.provider)
        routerModel = LLMExecutorBuilder.defaultModel(resolvedRouterConfig)
        LLMExecutorBuilder.build(resolvedRouterConfig)
    } ?: executor

    val sourceTracker = SourceTracker()
    val conversationStore = ConversationStore()
    val projectMemory = ProjectMemory()

    // Knowledge Base 초기화
    val knowledgeStore = KnowledgeStore()
    val knowledgeLlmFn: suspend (String) -> String = { userPrompt ->
        executor.execute(prompt("knowledge") { user(userPrompt) }, model).joinToString("") { it.content }
    }
    val knowledgeChromaFn: (suspend (String, String, String) -> Unit)? = if (config.rag.enabled) {
        val kbChromaClient = ChromaClient(config.rag.chromaUrl)
        val fn: suspend (String, String, String) -> Unit = { id, doc, _ ->
            val collectionId = kbChromaClient.getOrCreateCollection("knowledge_base")
            kbChromaClient.addDocuments(collectionId, listOf(id), listOf(doc))
        }
        fn
    } else null
    val ingestAgent = IngestAgent(knowledgeStore, knowledgeLlmFn, knowledgeChromaFn)
    val lintAgent = LintAgent(knowledgeStore, knowledgeLlmFn)
    val knowledgeTool = KnowledgeTool(knowledgeStore, sourceTracker)
    log.info("Knowledge base initialized: chromaFn={}", if (knowledgeChromaFn != null) "enabled" else "disabled")
    Runtime.getRuntime().addShutdownHook(Thread { ingestAgent.close() })

    // Confluence 클라이언트 생성
    var confluenceClient: ConfluenceClient? = null
    if (config.confluence.baseUrl.isNotBlank() && confluenceToken.isNotBlank()) {
        confluenceClient = ConfluenceClient(
            baseUrl = config.confluence.baseUrl,
            token = confluenceToken,
        )
        log.info("Confluence enabled: baseUrl={}, spaces={}", config.confluence.baseUrl, config.confluence.spaces)
    } else {
        log.info("Confluence disabled (baseUrl or token not set)")
    }

    // RAG 설정 (VectorSearchAgent를 ConfluenceSearchAgent에 주입하기 위해 먼저 생성)
    var vectorSearchTool: VectorSearchTool? = null
    var vectorSearchAgent: VectorSearchAgent? = null
    var vectorIndexAgent: VectorIndexAgent? = null

    if (config.rag.enabled) {
        val chromaClient = ChromaClient(config.rag.chromaUrl)
        val googleApiKey = SecretLoader.resolveNullable("GOOGLE_API_KEY", config.rag.googleApiKey)
        val llmFn: suspend (String) -> String = { userPrompt ->
            executor.execute(
                prompt("llm") { user(userPrompt) }, model
            ).joinToString("") { it.content }
        }
        val llmExpandClient = LlmExpandClient(llmFn)
        val googleEmbeddingClient = if (config.rag.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING)
            GoogleEmbeddingClient(requireNotNull(googleApiKey) { "GOOGLE_API_KEY required for GOOGLE_EMBEDDING mode" })
        else null

        vectorSearchAgent = VectorSearchAgent(chromaClient, llmExpandClient, googleEmbeddingClient, config.rag)
        vectorSearchTool = VectorSearchTool(vectorSearchAgent, sourceTracker)
        if (confluenceClient != null) {
            vectorIndexAgent = VectorIndexAgent(
                confluenceClient, chromaClient, llmExpandClient, googleEmbeddingClient, config.rag, config.confluence.spaces
            )
        } else {
            log.info("RAG indexing disabled (Confluence not configured)")
        }
        log.info("RAG enabled (mode={})", config.rag.embeddingMode)
    }

    // Confluence 검색 에이전트 (RAG 병렬 fallback 포함)
    var confluenceTool: ConfluenceTool? = null
    if (confluenceClient != null) {
        val confluenceSearchAgent = ConfluenceSearchAgent(
            confluenceClient = confluenceClient,
            spaces = config.confluence.spaces,
            vectorSearchAgent = vectorSearchAgent,
        )
        confluenceTool = ConfluenceTool(confluenceSearchAgent, sourceTracker)
    }

    // GitHub Wiki
    val githubToken = SecretLoader.resolve("GITHUB_TOKEN", config.github.token)
    var githubWikiTool: GitHubWikiTool? = null
    if (config.github.enabled && config.github.repos.isNotEmpty()) {
        val githubClient = GitHubWikiClient(githubToken)
        val githubWikiSearchAgent = GitHubWikiSearchAgent(githubClient, config.github.repos)
        githubWikiTool = GitHubWikiTool(githubWikiSearchAgent, sourceTracker)
        log.info("GitHub Wiki enabled: repos={}", config.github.repos)
    }

    // Code Search + PR History (codeRepos 설정 시)
    var prIndexAgent: PrIndexAgent? = null
    var codeIndexAgent: CodeIndexAgent? = null
    var prHistoryTool: PrHistoryTool? = null
    var codeSearchTool: CodeSearchTool? = null

    if (config.github.codeRepos.isNotEmpty() && config.rag.enabled) {
        val codeChromaClient = ChromaClient(config.rag.chromaUrl)
        val codeLlmFn: suspend (String) -> String = { userPrompt ->
            executor.execute(prompt("code") { user(userPrompt) }, model).joinToString("") { it.content }
        }
        val codeLlmExpandClient = LlmExpandClient(codeLlmFn)
        val githubCodeClient = GitHubCodeClient(githubToken)

        prIndexAgent = PrIndexAgent(
            codeClient = githubCodeClient,
            knowledgeStore = knowledgeStore,
            llmFn = codeLlmFn,
            chromaClient = codeChromaClient,
        )
        val sharedGoogleApiKey = SecretLoader.resolveNullable("GOOGLE_API_KEY", config.rag.googleApiKey)
        // 인덱싱/검색 API 키 분리 — 미설정 시 공용 키 fallback
        val indexApiKey = SecretLoader.resolveNullable("GOOGLE_INDEX_API_KEY", config.github.codeSearch.indexApiKey)
            ?: sharedGoogleApiKey
        val searchApiKey = SecretLoader.resolveNullable("GOOGLE_SEARCH_API_KEY", config.github.codeSearch.searchApiKey)
            ?: sharedGoogleApiKey

        val isGoogleEmbedding = config.github.codeSearch.embeddingMode == EmbeddingMode.GOOGLE_EMBEDDING
        val indexEmbeddingFn: (suspend (String) -> List<Float>)? =
            if (isGoogleEmbedding && indexApiKey != null)
                GoogleEmbeddingClient(indexApiKey).let { client -> { text: String -> client.embed(text) } }
            else null
        val searchEmbeddingFn: (suspend (String) -> List<Float>)? =
            if (isGoogleEmbedding && searchApiKey != null)
                GoogleEmbeddingClient(searchApiKey).let { client -> { text: String -> client.embed(text) } }
            else null

        if (indexEmbeddingFn == null) log.warn("Code indexing: no embedding function — using ChromaDB default embedding")
        if (searchEmbeddingFn == null) log.warn("Code search: no embedding function — using BM25 + grep only")

        // Step 3: BM25 인덱스 — localRepoPath 설정 시 활성화
        val bm25Index = if (config.github.codeSearch.localRepoPath != null) {
            BM25Index().also { log.info("BM25 hybrid search enabled") }
        } else null

        codeIndexAgent = CodeIndexAgent(
            codeClient = githubCodeClient,
            llmFn = codeLlmFn,
            chromaClient = codeChromaClient,
            repos = config.github.codeRepos,
            branch = config.github.codeSearch.branch,
            embeddingFn = indexEmbeddingFn,
            localRepoPath = config.github.codeSearch.localRepoPath,
            localRepoSync = config.github.codeSearch.localRepoPath?.let { LocalRepoSync(it) },
            bm25Index = bm25Index,
        )
        prHistoryTool = PrHistoryTool(codeChromaClient, codeLlmExpandClient, sourceTracker)
        codeSearchTool = CodeSearchTool(
            chromaClient = codeChromaClient,
            llmExpandClient = codeLlmExpandClient,
            codeClient = githubCodeClient,
            codeRepos = config.github.codeRepos,
            branch = config.github.codeSearch.branch,
            tracker = sourceTracker,
            bm25Index = bm25Index,
            embeddingFn = searchEmbeddingFn,
            localRepoPath = config.github.codeSearch.localRepoPath,
        )
        log.info("Code search enabled: repos={}, branch={}", config.github.codeRepos, config.github.codeSearch.branch)
    } else if (config.github.codeRepos.isNotEmpty()) {
        log.warn("codeRepos is set but rag.enabled=false — code search disabled. Enable RAG to use code search.")
    }

    // Call Graph (callGraph.cloneRepoPath 설정 시)
    var callGraphIndexAgent: CallGraphIndexAgent? = null
    var codeFlowTool: CodeFlowTool? = null
    config.callGraph?.let { cgCfg ->
        if (cgCfg.cloneRepoPath.isNotBlank()) {
            callGraphIndexAgent = CallGraphIndexAgent(
                cloneRepoPath = cgCfg.cloneRepoPath,
                dbPath = cgCfg.dbPath,
            )
            codeFlowTool = CodeFlowTool(cgCfg.dbPath)
            log.info("Call graph enabled: clone={}, db={}", cgCfg.cloneRepoPath, cgCfg.dbPath)
        }
    }

    val orchestrator = OrchestratorAgent(
        knowledgeTool = knowledgeTool,
        confluenceTool = confluenceTool,
        githubWikiTool = githubWikiTool,
        vectorSearchTool = vectorSearchTool,
        prHistoryTool = prHistoryTool,
        codeSearchTool = codeSearchTool,
        codeFlowTool = codeFlowTool,
        executor = executor,
        routerExecutor = routerExecutor,
        routerModel = routerModel,
        useManualLoop = config.model.provider == io.github.veronikapj.wiki.config.ModelProvider.CLAUDE_CODE ||
                config.model.provider == io.github.veronikapj.wiki.config.ModelProvider.GEMINI_CODE,
        conversationStore = conversationStore,
        projectMemory = projectMemory,
        persona = config.persona,
    )

    // 공유 백그라운드 스코프 (polling + webhook 공용)
    val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    Runtime.getRuntime().addShutdownHook(Thread { backgroundScope.cancel() })

    // Polling 코루틴 시작
    val finalPrIndexAgent = prIndexAgent
    if (finalPrIndexAgent != null && config.github.codeSearch.pollIntervalMinutes > 0) {
        backgroundScope.launch {
            val intervalMs = config.github.codeSearch.pollIntervalMinutes * 60_000L
            log.info("PR polling started: interval={}min, repos={}", config.github.codeSearch.pollIntervalMinutes, config.github.codeRepos)
            while (true) {
                delay(intervalMs)
                runCatching {
                    val count = finalPrIndexAgent.indexRecentPrs(config.github.codeRepos)
                    if (count > 0) log.info("Polling: indexed {} new PRs", count)
                }.onFailure { log.warn("Polling failed: {}", it.message) }
            }
        }
    }

    // Code incremental sync (localRepoPath 설정 시)
    val finalCodeIndexAgent = codeIndexAgent
    if (finalCodeIndexAgent != null && config.github.codeSearch.localRepoPath != null) {
        backgroundScope.launch {
            val intervalMs = config.github.codeSearch.pollIntervalMinutes * 60_000L
            log.info("Code incremental sync started: interval={}min", config.github.codeSearch.pollIntervalMinutes)
            while (true) {
                delay(intervalMs)
                runCatching {
                    val count = finalCodeIndexAgent.syncAndIndexChanged(config.github.codeRepos.first(), config.github.codeSearch.branch)
                    if (count > 0) log.info("Incremental code index: {} class entries updated", count)
                }.onFailure { log.warn("Incremental code sync failed: {}", it.message) }
            }
        }
    }

    // Call graph 증분 빌드 (60분 폴링)
    val finalCallGraphAgent = callGraphIndexAgent
    if (finalCallGraphAgent != null) {
        backgroundScope.launch {
            val intervalMs = config.github.codeSearch.pollIntervalMinutes * 60_000L
            log.info("Call graph polling started: interval={}min", config.github.codeSearch.pollIntervalMinutes)
            while (true) {
                delay(intervalMs)
                runCatching {
                    val ok = finalCallGraphAgent.runIndex()
                    if (ok) log.info("Call graph: index updated")
                }.onFailure { log.warn("Call graph build failed: {}", it.message) }
            }
        }
    }

    // GitHub Webhook 서버
    val finalPrIndexAgentForWebhook = prIndexAgent
    if (finalPrIndexAgentForWebhook != null && config.github.codeSearch.webhookPort > 0) {
        backgroundScope.launch {
            embeddedServer(CIO, port = config.github.codeSearch.webhookPort) {
                routing {
                    post("/webhook/github") {
                        val body = call.receiveText()
                        val event = call.request.headers["X-GitHub-Event"]
                        if (event == "pull_request") {
                            val action = Regex("\"action\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                            val merged = Regex("\"merged\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1) == "true"
                            val repo = Regex("\"full_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
                            val prNumber = Regex("\"number\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()

                            if (action == "closed" && merged && prNumber != null && repo.isNotBlank()) {
                                backgroundScope.launch {
                                    runCatching {
                                        finalPrIndexAgentForWebhook.indexPr(repo, prNumber)
                                        log.info("Webhook: indexed PR #{} from {}", prNumber, repo)
                                    }.onFailure { log.warn("Webhook indexing failed: {}", it.message) }
                                }
                            }
                        }
                        call.respond(io.ktor.http.HttpStatusCode.OK, "ok")
                    }
                }
            }.start(wait = false)
            log.info("GitHub webhook server started on port {}", config.github.codeSearch.webhookPort)
        }
    }

    val slackReady = slackBotToken.isNotBlank() && !slackBotToken.startsWith("xoxb-...") &&
            slackAppToken.isNotBlank() && !slackAppToken.startsWith("xapp-...")

    if (slackReady) {
        val configHandler = SlackConfigHandler(
            config = config,
            persistOnChange = true,
            onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
            onIngest = { url -> ingestAgent.ingestUrl(url) },
            onIngestWiki = { ingestAgent.ingestLocalWikiDocs() },
            onLint = { lintAgent.lint() },
            projectMemory = projectMemory,
            onReindexCode = codeIndexAgent?.let { agent -> { agent.indexAll() } },
            onReindexPr = prIndexAgent?.let { agent -> { agent.indexRecentPrs(config.github.codeRepos) } },
            onReindexPrFull = prIndexAgent?.let { agent -> { agent.indexPrsBulk(config.github.codeRepos, limit = 500) } },
        )
        // QueryRewriter: 기존 executor 재사용 (Haiku 모델로 비용 절감)
        val queryRewriter = QueryRewriter { prompt ->
            executor.execute(prompt("rewrite", LLMParams(maxTokens = 300)) { user(prompt) }, AnthropicModels.Haiku_4_5)
                .joinToString("") { it.content }
        }
        val gateway = SlackBotGateway(
            slackConfig = config.slack.copy(botToken = slackBotToken, appToken = slackAppToken),
            orchestrator = orchestrator,
            configHandler = configHandler,
            projectMemory = projectMemory,
            confluenceClient = confluenceClient,
            queryRewriter = queryRewriter,
        )
        gateway.start()
    } else {
        log.info("Slack tokens not set — running in local CLI mode")
        val divider = "─".repeat(60)
        println(divider)
        println("  wiki-agent CLI  |  GitHub Wiki: Veronikapj/wiki-agent")
        println("  로그: logs/wiki-agent.log  |  종료: q")
        println(divider)
        while (true) {
            println()
            print("질문 > ")
            val input = readlnOrNull()?.trim() ?: break
            if (input == "q") break
            if (input.isBlank()) continue
            println("       $input")
            println(divider)
            println("검색 중...")
            sourceTracker.reset()
            val result = kotlinx.coroutines.runBlocking { orchestrator.answer(input) }
            println()
            println(result)
            println()
            val sources = sourceTracker.sources
            if (sources.isNotEmpty()) {
                println("출처: ${sources.joinToString(" + ")}")
            } else {
                println("출처: 직접 답변 (tool 미사용)")
            }
            println(divider)
        }
        println("종료합니다.")
    }
}
