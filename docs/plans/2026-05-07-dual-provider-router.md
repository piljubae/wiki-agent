# Dual-Provider Router Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Gemini Flash를 라우팅 전용 LLM으로 사용해 Phase 1 응답시간을 10-15s → ~1s로 단축한다.

**Architecture:** `WikiConfig`에 `RouterConfig` 추가 → `ConfigLoader`에서 `router:` 섹션 파싱 → `OrchestratorAgent`에 `routerExecutor` 파라미터 추가(기본=`executor`) → `Main.kt`에서 분리 빌드 + 주입.

**Tech Stack:** Kotlin, Koog (`MultiLLMPromptExecutor`, `GoogleLLMClient`), hand-written YAML parser (ConfigLoader)

---

## Context

핵심 파일:
- `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt` — `ModelConfig`, `WikiConfig` 정의
- `src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt` — 직접 작성한 YAML 파서 (상태머신 방식)
- `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt` — `answerWithManualLoop()` 내 178번째 줄 `executor.execute(...)` = 라우팅 콜
- `src/main/kotlin/io/github/veronikapj/wiki/Main.kt` — `executor` 빌드 후 `OrchestratorAgent` 생성 (225번째 줄)
- `src/main/kotlin/io/github/veronikapj/wiki/llm/LLMExecutorBuilder.kt` — `build(ModelConfig)` 이미 GOOGLE 지원

현재 라우팅 콜 위치 (`OrchestratorAgent.kt:178`):
```kotlin
val decision = executor.execute(
    prompt("decision") { user(decisionPrompt) }, model
```
→ 이 `executor` 를 `routerExecutor` 로 교체하면 된다.

---

## Task 1: WikiConfig에 RouterConfig 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/config/ConfigLoaderTest.kt`

### Step 1: 테스트 먼저 작성 (실패 확인용)

`ConfigLoaderTest.kt` 하단에 두 테스트 추가:

```kotlin
@Test
fun `router section absent means routerConfig is null`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
        confluence:
          baseUrl: https://example.atlassian.net
          token: tok
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertEquals(null, config.routerConfig)
}

@Test
fun `router section with GOOGLE provider is parsed`() {
    val yaml = """
        model:
          provider: CLAUDE_CODE
        router:
          provider: GOOGLE
          apiKey: gkey
        confluence:
          baseUrl: https://example.atlassian.net
          token: tok
    """.trimIndent()
    val config = ConfigLoader.fromString(yaml)
    assertEquals(ModelProvider.GOOGLE, config.routerConfig?.provider)
    assertEquals("gkey", config.routerConfig?.apiKey)
}
```

### Step 2: 테스트 실패 확인

```bash
cd /Users/pilju.bae/projects/wiki-agent
./gradlew test --tests "*.ConfigLoaderTest.router*" 2>&1 | tail -20
```
Expected: FAIL — `Unresolved reference: routerConfig`

### Step 3: WikiConfig에 RouterConfig 추가

`WikiConfig.kt`에서 `WikiConfig` data class에 필드 추가:

```kotlin
data class WikiConfig(
    val model: ModelConfig = ModelConfig(),
    val confluence: ConfluenceConfig = ConfluenceConfig(),
    val slack: SlackConfig = SlackConfig(),
    val rag: RagConfig = RagConfig(),
    val github: GithubConfig = GithubConfig(),
    val persona: PersonaType = PersonaType.DEFAULT,
    val routerConfig: ModelConfig? = null,   // ← 추가
)
```

`ModelConfig`를 재사용 (provider + name + apiKey 구조 동일).

### Step 4: 테스트 통과 확인

```bash
./gradlew test --tests "*.ConfigLoaderTest.router*" 2>&1 | tail -20
```
Expected: FAIL — `routerConfig` 는 존재하지만 파싱이 안 됨 (null 반환)
→ 첫 번째 테스트 (`null` 기대) 는 PASS, 두 번째 (`GOOGLE`) 는 FAIL

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt \
        src/test/kotlin/io/github/veronikapj/wiki/config/ConfigLoaderTest.kt
git commit -m "feat: add routerConfig field to WikiConfig"
```

---

## Task 2: ConfigLoader에 router: 섹션 파싱 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt`

### Step 1: ConfigLoader.kt에 router 파싱 추가

`fromString()` 메서드 안에서 `model:` 파싱과 동일한 방식으로 `router:` 를 파싱한다.

변수 선언부에 추가 (기존 `var inModel = false` 근처):
```kotlin
var inRouter = false
var routerProvider: ModelProvider? = null
var routerModelName: String? = null
var routerApiKey: String? = null
```

섹션 전환 `when` 블록 (`line == "model:" ->` 근처)에 추가:
```kotlin
line == "router:" -> {
    inRouter = true
    inModel = false; inConfluence = false; inSlack = false
    inSpaces = false; inRag = false; inGithub = false; inCodeSearch = false
}
```

기존 `inModel` 전환 라인들에 `inRouter = false` 추가:
```kotlin
line == "model:" -> { inModel = true; inRouter = false; inConfluence = false; ... }
line == "confluence:" -> { inConfluence = true; inRouter = false; inModel = false; ... }
line == "slack:" -> { inSlack = true; inRouter = false; inModel = false; ... }
line == "rag:" -> { inRag = true; inRouter = false; inModel = false; ... }
line == "github:" -> { inGithub = true; inRouter = false; inModel = false; ... }
```

값 파싱 `when` 블록 (`inModel && trimmed.startsWith("provider:")` 근처)에 추가:
```kotlin
inRouter && trimmed.startsWith("provider:") ->
    routerProvider = runCatching {
        ModelProvider.valueOf(trimmed.substringAfter("provider:").trim().uppercase())
    }.getOrNull()
inRouter && trimmed.startsWith("name:") ->
    routerModelName = trimmed.substringAfter("name:").trim().ifEmpty { null }
inRouter && trimmed.startsWith("apiKey:") ->
    routerApiKey = trimmed.substringAfter("apiKey:").trim().ifEmpty { null }
```

`return WikiConfig(...)` 에 `routerConfig` 추가:
```kotlin
return WikiConfig(
    model = ModelConfig(provider, modelName, apiKey),
    ...
    persona = persona,
    routerConfig = routerProvider?.let { ModelConfig(it, routerModelName, routerApiKey) },
)
```

### Step 2: 테스트 통과 확인

```bash
./gradlew test --tests "*.ConfigLoaderTest" 2>&1 | tail -20
```
Expected: 모든 테스트 PASS (기존 6개 + 새 2개 = 8개)

### Step 3: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/config/ConfigLoader.kt
git commit -m "feat: parse router: section in ConfigLoader"
```

---

## Task 3: OrchestratorAgent에 routerExecutor 파라미터 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt`

### Step 1: 테스트 먼저 작성

`OrchestratorAgentTest.kt`에 추가:

```kotlin
@Test
fun `routerExecutor defaults to executor when not specified`() {
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
    val executor = LLMExecutorBuilder.build(ModelConfig())
    val agent = OrchestratorAgent(
        confluenceTool = confluenceTool,
        executor = executor,
        // routerExecutor 미지정 — 기본값 = executor
    )
    assertNotNull(agent)
}

@Test
fun `routerExecutor can be set independently`() {
    val confluenceTool = ConfluenceTool(mockk<ConfluenceSearchAgent>())
    val executor = LLMExecutorBuilder.build(ModelConfig())
    val routerExecutor = LLMExecutorBuilder.build(ModelConfig())
    val agent = OrchestratorAgent(
        confluenceTool = confluenceTool,
        executor = executor,
        routerExecutor = routerExecutor,
    )
    assertNotNull(agent)
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew test --tests "*.OrchestratorAgentTest.routerExecutor*" 2>&1 | tail -20
```
Expected: FAIL — `routerExecutor` 파라미터 없음

### Step 3: OrchestratorAgent에 routerExecutor 파라미터 추가

클래스 선언부에 파라미터 추가:
```kotlin
class OrchestratorAgent(
    private val knowledgeTool: KnowledgeTool? = null,
    private val confluenceTool: ConfluenceTool? = null,
    private val githubWikiTool: GitHubWikiTool? = null,
    private val vectorSearchTool: VectorSearchTool? = null,
    private val prHistoryTool: PrHistoryTool? = null,
    private val codeSearchTool: CodeSearchTool? = null,
    private val executor: MultiLLMPromptExecutor,
    private val routerExecutor: MultiLLMPromptExecutor = executor,  // ← 추가
    private val useManualLoop: Boolean = false,
    private val conversationStore: ConversationStore? = null,
    private val projectMemory: ProjectMemory? = null,
    private val persona: io.github.veronikapj.wiki.config.PersonaType = ...,
)
```

`answerWithManualLoop()` 내 라우팅 콜 교체 (현재 178번째 줄):
```kotlin
// Before:
val decision = executor.execute(
    prompt("decision") { user(decisionPrompt) }, model
).joinToString("") { it.content }

// After:
val decision = routerExecutor.execute(
    prompt("decision") { user(decisionPrompt) }, model
).joinToString("") { it.content }
```

### Step 4: 테스트 통과 확인

```bash
./gradlew test --tests "*.OrchestratorAgentTest" 2>&1 | tail -20
```
Expected: 모든 테스트 PASS

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/test/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgentTest.kt
git commit -m "feat: add routerExecutor param to OrchestratorAgent"
```

---

## Task 4: Main.kt에서 routerExecutor 빌드 + 주입

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

### Step 1: routerExecutor 빌드 로직 추가

`Main.kt`에서 `val executor = LLMExecutorBuilder.build(resolvedModelConfig)` 다음에:

```kotlin
val executor = LLMExecutorBuilder.build(resolvedModelConfig)
val model = LLMExecutorBuilder.defaultModel(resolvedModelConfig)

// Router executor: routerConfig 있으면 별도 빌드, 없으면 executor 재사용
val routerExecutor = config.routerConfig?.let { routerCfg ->
    val resolvedRouterApiKey = when (routerCfg.provider) {
        io.github.veronikapj.wiki.config.ModelProvider.GOOGLE ->
            SecretLoader.resolveNullable("GOOGLE_API_KEY", routerCfg.apiKey)
        io.github.veronikapj.wiki.config.ModelProvider.ANTHROPIC ->
            SecretLoader.resolveNullable("ANTHROPIC_API_KEY", routerCfg.apiKey)
        else -> routerCfg.apiKey
    }
    val resolvedRouterConfig = routerCfg.copy(apiKey = resolvedRouterApiKey)
    log.info("Router executor: provider={}", resolvedRouterConfig.provider)
    LLMExecutorBuilder.build(resolvedRouterConfig)
} ?: executor
```

### Step 2: OrchestratorAgent 생성에 routerExecutor 주입

기존 `OrchestratorAgent(...)` 블록에 `routerExecutor = routerExecutor` 추가:

```kotlin
val orchestrator = OrchestratorAgent(
    knowledgeTool = knowledgeTool,
    confluenceTool = confluenceTool,
    githubWikiTool = githubWikiTool,
    vectorSearchTool = vectorSearchTool,
    prHistoryTool = prHistoryTool,
    codeSearchTool = codeSearchTool,
    executor = executor,
    routerExecutor = routerExecutor,   // ← 추가
    useManualLoop = config.model.provider == ...,
    conversationStore = conversationStore,
    projectMemory = projectMemory,
    persona = config.persona,
)
```

### Step 3: 컴파일 확인

```bash
./gradlew compileKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

### Step 4: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git commit -m "feat: build and inject routerExecutor in Main"
```

---

## Task 5: config.yml 업데이트 + 통합 테스트

**Files:**
- Modify: `.wikiq/config.yml`

### Step 1: router 섹션 추가

`.wikiq/config.yml`에 추가:

```yaml
model:
  provider: CLAUDE_CODE  # CLAUDE_CODE | ANTHROPIC | GOOGLE

router:
  provider: GOOGLE       # 라우팅 전용 — Gemini Flash (~1s)
  # apiKey: ...          # 생략 시 GOOGLE_API_KEY 환경변수 사용

confluence:
  ...
```

apiKey는 생략 → `SecretLoader.resolveNullable("GOOGLE_API_KEY", null)` 가 환경변수에서 읽음.

### Step 2: 전체 테스트 실행

```bash
./gradlew test 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL, 모든 기존 테스트 PASS

### Step 3: 봇 재시작 + 응답 시간 측정

기존 프로세스 종료 후 재시작:
```bash
pkill -f "wiki-agent" 2>/dev/null; sleep 1
./gradlew run > logs/wiki-agent.log 2>&1 &
sleep 5
tail -20 logs/wiki-agent.log
```

로그에서 확인:
- `Router executor: provider=GOOGLE` 라인 존재
- Slack에서 질문 후 "검색 중..." 응답이 ~1s 안에 오는지 확인

### Step 4: 커밋

```bash
git add .wikiq/config.yml
git commit -m "config: enable Gemini Flash as router provider"
```

---

## 완료 기준

- [ ] `ConfigLoaderTest` 8개 모두 PASS
- [ ] `OrchestratorAgentTest` 모든 테스트 PASS
- [ ] 봇 시작 로그에 `Router executor: provider=GOOGLE` 확인
- [ ] Slack에서 질문 시 "검색 중..." 응답이 기존 10-15s → ~1s로 단축
