# Call Graph Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Kotlin IR 컴파일러 플러그인으로 Kurly Android 콜 그래프를 추출하고 Slack에서 코드 흐름 질문(역방향 참조 / 레이어 체인 / 임팩트 분석)에 답한다.

**Architecture:** `callgraph-plugin` 서브프로젝트가 `IrGenerationExtension`으로 `IrCall`을 방문해 SQLite에 저장. init.gradle.kts로 Kurly Android 클론에 주입(소스 수정 없음). 기존 60분 폴링 사이클에 `./gradlew compileDebugKotlin` 증분 빌드를 추가. `CodeFlowTool`이 findCallers / traceChain / findImpact 세 가지 쿼리를 제공.

**Tech Stack:** Kotlin 2.x `CompilerPluginRegistrar`, `IrGenerationExtension`, `org.xerial:sqlite-jdbc` (이미 추가됨), JUnit5, MockK

---

## Context

핵심 파일:
- `settings.gradle.kts` — 서브프로젝트 include 추가
- `build.gradle.kts` — callgraph-plugin 의존성 추가
- `src/main/kotlin/io/github/veronikapj/wiki/knowledge/CallGraphIndexAgent.kt` — 신규
- `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/CodeFlowTool.kt` — 신규
- `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt` — 툴/프롬프트 추가
- `src/main/kotlin/io/github/veronikapj/wiki/Main.kt` — 폴링 통합

주의: wiki-agent는 Kotlin 2.3.0 (K2) 사용. 플러그인은 `CompilerPluginRegistrar` (K2 API) 기반.

---

## Task 1: callgraph-plugin 서브프로젝트 골격

**Files:**
- Modify: `settings.gradle.kts`
- Create: `callgraph-plugin/build.gradle.kts`
- Create: `callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphPluginRegistrar.kt`
- Create: `callgraph-plugin/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar`

### Step 1: settings.gradle.kts에 서브프로젝트 추가

```kotlin
// settings.gradle.kts
rootProject.name = "wiki-agent"
include("callgraph-plugin")
```

### Step 2: callgraph-plugin/build.gradle.kts 작성

```kotlin
plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "io.github.veronikapj"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") { from(components["java"]) }
    }
    repositories { mavenLocal() }
}
```

### Step 3: CallGraphPluginRegistrar 작성

```kotlin
// callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphPluginRegistrar.kt
package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

val OUTPUT_PATH_KEY = CompilerConfigurationKey.create<String>("callgraph.outputPath")

@OptIn(ExperimentalCompilerApi::class)
class CallGraphPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val outputPath = configuration[OUTPUT_PATH_KEY] ?: return
        IrGenerationExtension.registerExtension(CallGraphIrExtension(outputPath))
    }
}
```

### Step 4: 서비스 파일 작성

```
// callgraph-plugin/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
io.github.veronikapj.callgraph.CallGraphPluginRegistrar
```

### Step 5: 컴파일 확인

```bash
cd /Users/pilju.bae/projects/wiki-agent
./gradlew :callgraph-plugin:compileKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

### Step 6: 커밋

```bash
git add settings.gradle.kts callgraph-plugin/
git commit -m "feat: add callgraph-plugin subproject skeleton"
```

---

## Task 2: CallGraphDb + IrGenerationExtension 구현

**Files:**
- Create: `callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphDb.kt`
- Create: `callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphIrExtension.kt`
- Create: `callgraph-plugin/src/test/kotlin/io/github/veronikapj/callgraph/CallGraphDbTest.kt`

### Step 1: 실패 테스트 작성

```kotlin
// callgraph-plugin/src/test/kotlin/io/github/veronikapj/callgraph/CallGraphDbTest.kt
package io.github.veronikapj.callgraph

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallGraphDbTest {
    private lateinit var dbFile: File
    private lateinit var db: CallGraphDb

    @BeforeEach fun setup() {
        dbFile = File.createTempFile("test_cg", ".db")
        db = CallGraphDb(dbFile.absolutePath)
    }
    @AfterEach fun teardown() { db.close(); dbFile.delete() }

    @Test
    fun `upsertEdge and findCallers round-trip`() {
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        db.upsertEdge("com.kurly.HomeViewModel.load", "com.kurly.GetProductUseCase.invoke", "HomeViewModel.kt")

        val callers = db.findCallers("com.kurly.GetProductUseCase.invoke")
        assertEquals(2, callers.size)
        assertTrue(callers.any { it.callerFqn == "com.kurly.ProductViewModel.load" })
    }

    @Test
    fun `findCallees returns direct callees`() {
        db.upsertEdge("com.kurly.GetProductUseCase.invoke", "com.kurly.ProductRepository.get", "GetProductUseCase.kt")
        val callees = db.findCallees("com.kurly.GetProductUseCase.invoke")
        assertEquals(1, callees.size)
        assertEquals("com.kurly.ProductRepository.get", callees.first().calleeFqn)
    }

    @Test
    fun `findCallersLike matches partial FQN`() {
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        val callers = db.findCallersLike("GetProductUseCase")
        assertEquals(1, callers.size)
    }

    @Test
    fun `upsert is idempotent`() {
        db.upsertEdge("A.foo", "B.bar", "A.kt")
        db.upsertEdge("A.foo", "B.bar", "A.kt")
        assertEquals(1, db.findCallers("B.bar").size)
    }
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew :callgraph-plugin:test 2>&1 | tail -5
```
Expected: FAIL — CallGraphDb not found

### Step 3: CallGraphDb 구현

```kotlin
// callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphDb.kt
package io.github.veronikapj.callgraph

import java.sql.DriverManager

data class CallEdge(val callerFqn: String, val calleeFqn: String, val callerFile: String)

class CallGraphDb(private val dbPath: String) {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { c ->
        c.autoCommit = false
        c.createStatement().executeUpdate("""
            CREATE TABLE IF NOT EXISTS call_edges (
                caller_fqn TEXT NOT NULL,
                callee_fqn TEXT NOT NULL,
                caller_file TEXT,
                PRIMARY KEY (caller_fqn, callee_fqn)
            )
        """)
        c.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_callee ON call_edges(callee_fqn)
        """)
        c.createStatement().executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_caller ON call_edges(caller_fqn)
        """)
        c.commit()
    }

    fun upsertEdge(callerFqn: String, calleeFqn: String, callerFile: String) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO call_edges(caller_fqn, callee_fqn, caller_file) VALUES(?,?,?)"
        ).use {
            it.setString(1, callerFqn); it.setString(2, calleeFqn); it.setString(3, callerFile)
            it.executeUpdate()
        }
        conn.commit()
    }

    fun findCallers(calleeFqn: String): List<CallEdge> = query(
        "SELECT caller_fqn, callee_fqn, caller_file FROM call_edges WHERE callee_fqn = ?", calleeFqn
    )

    fun findCallees(callerFqn: String): List<CallEdge> = query(
        "SELECT caller_fqn, callee_fqn, caller_file FROM call_edges WHERE caller_fqn = ?", callerFqn
    )

    fun findCallersLike(pattern: String): List<CallEdge> = query(
        "SELECT caller_fqn, callee_fqn, caller_file FROM call_edges WHERE callee_fqn LIKE ?", "%$pattern%"
    )

    fun findCalleesLike(pattern: String): List<CallEdge> = query(
        "SELECT caller_fqn, callee_fqn, caller_file FROM call_edges WHERE caller_fqn LIKE ?", "%$pattern%"
    )

    private fun query(sql: String, param: String): List<CallEdge> {
        val stmt = conn.prepareStatement(sql)
        stmt.setString(1, param)
        val rs = stmt.executeQuery()
        val result = mutableListOf<CallEdge>()
        while (rs.next()) result += CallEdge(rs.getString(1), rs.getString(2), rs.getString(3) ?: "")
        return result
    }

    fun close() = conn.close()
}
```

### Step 4: CallGraphIrExtension 구현

```kotlin
// callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphIrExtension.kt
package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

class CallGraphIrExtension(private val outputPath: String) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val db = CallGraphDb(outputPath)
        try {
            moduleFragment.accept(CallGraphVisitor(db), null)
        } finally {
            db.close()
        }
    }
}

class CallGraphVisitor(private val db: CallGraphDb) : IrElementVisitorVoid {
    private val functionStack = ArrayDeque<IrFunction>()

    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

    override fun visitFunction(declaration: IrFunction) {
        functionStack.addLast(declaration)
        super.visitFunction(declaration)
        functionStack.removeLast()
    }

    override fun visitCall(expression: IrCall) {
        val caller = functionStack.lastOrNull() ?: run { super.visitCall(expression); return }
        val callerFqn = caller.kotlinFqName?.asString() ?: run { super.visitCall(expression); return }
        val calleeFqn = runCatching { expression.symbol.owner.kotlinFqName?.asString() }
            .getOrNull() ?: run { super.visitCall(expression); return }

        // kotlin.* / java.* stdlib 제외
        if (!calleeFqn.startsWith("kotlin.") && !calleeFqn.startsWith("java.")) {
            val callerFile = caller.fileEntry?.name ?: ""
            db.upsertEdge(callerFqn, calleeFqn, callerFile)
        }
        super.visitCall(expression)
    }
}
```

### Step 5: 테스트 통과 확인

```bash
./gradlew :callgraph-plugin:test 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

### Step 6: 커밋

```bash
git add callgraph-plugin/src/
git commit -m "feat: implement CallGraphDb and IrGenerationExtension"
```

---

## Task 3: CommandLineProcessor + init.gradle.kts 설정 스크립트

**Files:**
- Create: `callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphCommandLineProcessor.kt`
- Create: `callgraph-plugin/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor`
- Create: `scripts/setup-callgraph.sh`

### Step 1: CommandLineProcessor 작성

```kotlin
// callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphCommandLineProcessor.kt
package io.github.veronikapj.callgraph

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class CallGraphCommandLineProcessor : CommandLineProcessor {
    override val pluginId = "io.github.veronikapj.callgraph"
    override val pluginOptions = listOf(
        CliOption("outputPath", "<path>", "call_graph.db 출력 경로", required = true)
    )
    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        if (option.optionName == "outputPath") configuration.put(OUTPUT_PATH_KEY, value)
    }
}
```

서비스 파일:
```
// META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
io.github.veronikapj.callgraph.CallGraphCommandLineProcessor
```

### Step 2: mavenLocal 배포

```bash
./gradlew :callgraph-plugin:publishToMavenLocal 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL
배포 경로: `~/.m2/repository/io/github/veronikapj/callgraph-plugin/1.0.0/`

### Step 3: setup-callgraph.sh 작성

```bash
#!/bin/bash
# scripts/setup-callgraph.sh
# 사용법: ./scripts/setup-callgraph.sh <clone-path> <db-output-path>
set -e

CLONE_PATH="${1:?clone path required}"
DB_PATH="${2:-$(pwd)/call_graph.db}"
PLUGIN_JAR="$HOME/.m2/repository/io/github/veronikapj/callgraph-plugin/1.0.0/callgraph-plugin-1.0.0.jar"
SQLITE_JAR="$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.47.1.0/sqlite-jdbc-3.47.1.0.jar"

if [ ! -f "$PLUGIN_JAR" ]; then
  echo "ERROR: Plugin JAR not found. Run: ./gradlew :callgraph-plugin:publishToMavenLocal"
  exit 1
fi

cat > "$CLONE_PATH/init.gradle.kts" << EOF
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xplugin=$PLUGIN_JAR",
            "-Xplugin=$SQLITE_JAR",
            "-P", "plugin:io.github.veronikapj.callgraph:outputPath=$DB_PATH"
        )
    }
}
EOF

echo "init.gradle.kts written to $CLONE_PATH/init.gradle.kts"
echo "DB output: $DB_PATH"
```

```bash
chmod +x scripts/setup-callgraph.sh
```

### Step 4: 커밋

```bash
git add callgraph-plugin/src/main/kotlin/io/github/veronikapj/callgraph/CallGraphCommandLineProcessor.kt
git add callgraph-plugin/src/main/resources/META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
git add scripts/setup-callgraph.sh
git commit -m "feat: add CommandLineProcessor and init.gradle setup script"
```

---

## Task 4: CallGraphIndexAgent 구현

**Files:**
- Modify: `build.gradle.kts` — callgraph-plugin 의존성 추가
- Create: `src/main/kotlin/io/github/veronikapj/wiki/knowledge/CallGraphIndexAgent.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/knowledge/CallGraphIndexAgentTest.kt`

### Step 1: build.gradle.kts에 의존성 추가

```kotlin
// build.gradle.kts dependencies 블록에 추가
implementation(project(":callgraph-plugin"))
```

### Step 2: 실패 테스트 작성

```kotlin
// CallGraphIndexAgentTest.kt
package io.github.veronikapj.wiki.knowledge

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CallGraphIndexAgentTest {

    @Test
    fun `buildGradleCommand includes compileDebugKotlin and build-cache`() {
        val agent = CallGraphIndexAgent(cloneRepoPath = "/tmp/kurly", dbPath = "/tmp/cg.db")
        val cmd = agent.buildGradleCommand()
        assertTrue(cmd.contains("compileDebugKotlin"), "must target compileDebugKotlin")
        assertTrue(cmd.contains("--build-cache"), "must use incremental build cache")
    }
}
```

### Step 3: 테스트 실패 확인

```bash
./gradlew test --tests "*.CallGraphIndexAgentTest" 2>&1 | tail -5
```
Expected: FAIL

### Step 4: CallGraphIndexAgent 구현

```kotlin
// CallGraphIndexAgent.kt
package io.github.veronikapj.wiki.knowledge

import io.github.veronikapj.callgraph.CallGraphDb
import org.slf4j.LoggerFactory
import java.io.File

class CallGraphIndexAgent(
    val cloneRepoPath: String,
    val dbPath: String,
) {
    fun buildGradleCommand() = listOf(
        "./gradlew", "compileDebugKotlin",
        "--build-cache",
        "--no-daemon",
        "--quiet",
    )

    fun openDb(): CallGraphDb = CallGraphDb(dbPath)

    fun runIndex(): Boolean {
        log.info("CallGraph: incremental build in {}", cloneRepoPath)
        return runCatching {
            val proc = ProcessBuilder(buildGradleCommand())
                .directory(File(cloneRepoPath))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                log.warn("Gradle failed (exit={}): {}", exit, output.takeLast(300))
                false
            } else {
                log.info("CallGraph: build complete, DB at {}", dbPath)
                true
            }
        }.onFailure { log.warn("CallGraph build error: {}", it.message) }.getOrDefault(false)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CallGraphIndexAgent::class.java)
    }
}
```

### Step 5: 테스트 통과 확인

```bash
./gradlew test --tests "*.CallGraphIndexAgentTest" 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

### Step 6: 커밋

```bash
git add build.gradle.kts
git add src/main/kotlin/io/github/veronikapj/wiki/knowledge/CallGraphIndexAgent.kt
git add src/test/kotlin/io/github/veronikapj/wiki/knowledge/CallGraphIndexAgentTest.kt
git commit -m "feat: add CallGraphIndexAgent"
```

---

## Task 5: CodeFlowTool (findCallers / traceChain / findImpact)

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/agent/tool/CodeFlowTool.kt`
- Create: `src/test/kotlin/io/github/veronikapj/wiki/agent/tool/CodeFlowToolTest.kt`

### Step 1: 실패 테스트 작성

```kotlin
// CodeFlowToolTest.kt
package io.github.veronikapj.wiki.agent.tool

import io.github.veronikapj.callgraph.CallGraphDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class CodeFlowToolTest {
    private lateinit var dbFile: File
    private lateinit var db: CallGraphDb
    private lateinit var tool: CodeFlowTool

    @BeforeEach fun setup() {
        dbFile = File.createTempFile("cg_tool", ".db")
        db = CallGraphDb(dbFile.absolutePath)
        // VM → UseCase → Repo → Api
        db.upsertEdge("com.kurly.ProductViewModel.load", "com.kurly.GetProductUseCase.invoke", "ProductViewModel.kt")
        db.upsertEdge("com.kurly.GetProductUseCase.invoke", "com.kurly.ProductRepository.get", "GetProductUseCase.kt")
        db.upsertEdge("com.kurly.ProductRepository.get", "com.kurly.ProductApi.fetch", "ProductRepository.kt")
        db.upsertEdge("com.kurly.HomeViewModel.load", "com.kurly.GetProductUseCase.invoke", "HomeViewModel.kt")
        db.close()
        tool = CodeFlowTool(dbFile.absolutePath)
    }
    @AfterEach fun teardown() { dbFile.delete() }

    @Test
    fun `findCallers returns all callers`() {
        val result = tool.findCallers("GetProductUseCase.invoke")
        assertTrue(result.contains("ProductViewModel.load"))
        assertTrue(result.contains("HomeViewModel.load"))
    }

    @Test
    fun `traceChain follows forward call chain`() {
        val result = tool.traceChain("ProductViewModel.load")
        assertTrue(result.contains("GetProductUseCase"))
        assertTrue(result.contains("ProductRepository"))
        assertTrue(result.contains("ProductApi"))
    }

    @Test
    fun `findImpact returns reverse transitive closure`() {
        val result = tool.findImpact("ProductApi.fetch")
        assertTrue(result.contains("ProductRepository"))
        assertTrue(result.contains("GetProductUseCase"))
        assertTrue(result.contains("ProductViewModel"))
        assertTrue(result.contains("HomeViewModel"))
    }

    @Test
    fun `findCallers returns not-found message when no callers`() {
        val result = tool.findCallers("NonExistent.function")
        assertTrue(result.contains("찾지 못했습니다"))
    }
}
```

### Step 2: 테스트 실패 확인

```bash
./gradlew test --tests "*.CodeFlowToolTest" 2>&1 | tail -5
```
Expected: FAIL

### Step 3: CodeFlowTool 구현

```kotlin
// CodeFlowTool.kt
package io.github.veronikapj.wiki.agent.tool

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import io.github.veronikapj.callgraph.CallGraphDb

class CodeFlowTool(private val dbPath: String) {

    @Tool("findCallers")
    @LLMDescription(
        "특정 함수를 호출하는 위치를 모두 찾습니다. " +
        "'loadProduct 어디서 불려?', '이 UseCase 누가 호출해?' 질문에 사용하세요."
    )
    fun findCallers(
        @LLMDescription("찾을 함수명 또는 FQN 일부. 예: GetProductUseCase.invoke, loadProduct")
        functionName: String,
    ): String {
        val db = CallGraphDb(dbPath)
        return runCatching {
            val edges = db.findCallersLike(functionName)
            if (edges.isEmpty()) return@runCatching "`$functionName`을 호출하는 곳을 찾지 못했습니다."
            buildString {
                appendLine("*`$functionName` 호출 위치 [${edges.size}건]:*\n")
                edges.take(10).forEach { e ->
                    appendLine("• `${e.callerFqn}`")
                    if (e.callerFile.isNotBlank()) appendLine("  _${e.callerFile}_")
                }
                if (edges.size > 10) appendLine("\n_... 외 ${edges.size - 10}건_")
            }.trim()
        }.getOrElse { "콜 그래프 조회 중 오류: ${it.message}" }.also { db.close() }
    }

    @Tool("traceChain")
    @LLMDescription(
        "함수에서 시작해 호출 체인을 순방향으로 추적합니다. " +
        "'ViewModel→Repository 흐름', '상품 로드 레이어 경로' 질문에 사용하세요."
    )
    fun traceChain(
        @LLMDescription("시작 함수명 또는 FQN 일부. 예: ProductDetailViewModel.loadProduct")
        functionName: String,
        @LLMDescription("최대 탐색 깊이 (기본 5)")
        maxDepth: Int = 5,
    ): String {
        val db = CallGraphDb(dbPath)
        return runCatching {
            val startEdges = db.findCalleesLike(functionName)
            if (startEdges.isEmpty()) return@runCatching "`$functionName`의 호출 체인을 찾지 못했습니다."
            val visited = mutableSetOf<String>()
            val lines = mutableListOf<String>()
            val startFqn = startEdges.first().callerFqn
            lines += "`$startFqn`"
            fun bfs(fqn: String, depth: Int) {
                if (depth > maxDepth || !visited.add(fqn)) return
                db.findCallees(fqn).forEach { e ->
                    lines += "${"  ".repeat(depth)}→ `${e.calleeFqn}`"
                    bfs(e.calleeFqn, depth + 1)
                }
            }
            bfs(startFqn, 1)
            buildString {
                appendLine("*`$functionName` 호출 체인:*\n")
                appendLine(lines.joinToString("\n"))
            }.trim()
        }.getOrElse { "체인 추적 중 오류: ${it.message}" }.also { db.close() }
    }

    @Tool("findImpact")
    @LLMDescription(
        "함수 변경 시 영향받는 곳을 역방향으로 추적합니다. " +
        "'panelCode 바꾸면 어디 영향?', '이 함수 변경의 파급 범위' 질문에 사용하세요."
    )
    fun findImpact(
        @LLMDescription("변경할 함수명 또는 FQN 일부. 예: SectionMapper.mapPanelCode")
        functionName: String,
        @LLMDescription("최대 탐색 깊이 (기본 5)")
        maxDepth: Int = 5,
    ): String {
        val db = CallGraphDb(dbPath)
        return runCatching {
            val visited = mutableSetOf<String>()
            val affected = mutableListOf<Pair<String, String>>()
            fun bfs(fqn: String, depth: Int) {
                if (depth > maxDepth) return
                db.findCallersLike(fqn).forEach { e ->
                    if (visited.add(e.callerFqn)) {
                        affected += e.callerFqn to e.callerFile
                        bfs(e.callerFqn, depth + 1)
                    }
                }
            }
            bfs(functionName, 1)
            if (affected.isEmpty()) return@runCatching "`$functionName` 변경의 영향 범위를 찾지 못했습니다."
            buildString {
                appendLine("*`$functionName` 변경 영향 범위 [${affected.size}건]:*\n")
                affected.take(15).forEach { (fqn, file) ->
                    appendLine("• `$fqn`")
                    if (file.isNotBlank()) appendLine("  _$file_")
                }
                if (affected.size > 15) appendLine("\n_... 외 ${affected.size - 15}건_")
            }.trim()
        }.getOrElse { "임팩트 분석 중 오류: ${it.message}" }.also { db.close() }
    }
}
```

### Step 4: 테스트 통과 확인

```bash
./gradlew test --tests "*.CodeFlowToolTest" 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/tool/CodeFlowTool.kt
git add src/test/kotlin/io/github/veronikapj/wiki/agent/tool/CodeFlowToolTest.kt
git commit -m "feat: add CodeFlowTool — findCallers/traceChain/findImpact"
```

---

## Task 6: OrchestratorAgent + Main.kt 연결

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt`

### Step 1: WikiConfig에 callGraph 설정 추가

`WikiConfig.kt`의 `CodeSearchConfig` 또는 별도 섹션에 추가:
```kotlin
data class CallGraphConfig(
    val cloneRepoPath: String = "",
    val dbPath: String = "call_graph.db",
)
```

`ConfigLoader.kt`에서 YAML 파싱 추가 (기존 codeSearch 섹션 패턴 참고).

### Step 2: OrchestratorAgent에 CodeFlowTool 등록

생성자에 `codeFlowTool: CodeFlowTool? = null` 추가.

툴 목록에 등록 (기존 `codeSearchTool` 등록 패턴 참고):
```kotlin
codeFlowTool?.let { tools.add(it) }
```

라우터 프롬프트 도구 설명 섹션에 추가:
```kotlin
if (codeFlowTool != null) {
    appendLine("- findCallers: 함수를 호출하는 곳 추적. '어디서 불려?', '누가 호출해?' 질문.")
    appendLine("- traceChain: 호출 체인 순방향 추적. 'ViewModel→Repository 흐름', '레이어 경로' 질문.")
    appendLine("- findImpact: 변경 임팩트 역방향 추적. '바꾸면 어디 영향?', '파급 범위' 질문.")
}
```

### Step 3: Main.kt에 CallGraphIndexAgent 생성 및 폴링 통합

```kotlin
// CallGraphIndexAgent 생성 (callGraph 설정 시)
val callGraphAgent = config.callGraph?.let {
    if (it.cloneRepoPath.isNotBlank()) CallGraphIndexAgent(it.cloneRepoPath, it.dbPath) else null
}

// CodeFlowTool 생성
val codeFlowTool = callGraphAgent?.let { CodeFlowTool(it.dbPath) }
```

기존 Code incremental sync 블록 (`syncAndIndexChanged` 호출 후) 에 추가:
```kotlin
// 콜 그래프 증분 빌드 (ChromaDB 인덱싱과 같은 주기)
finalCallGraphAgent?.runIndex()
```

### Step 4: 전체 테스트

```bash
./gradlew test 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

### Step 5: 커밋

```bash
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt
git add src/main/kotlin/io/github/veronikapj/wiki/Main.kt
git add src/main/kotlin/io/github/veronikapj/wiki/config/WikiConfig.kt
git commit -m "feat: wire CodeFlowTool and CallGraphIndexAgent into 60min polling cycle"
```

---

## 완료 기준

- [ ] `./gradlew :callgraph-plugin:test` PASS
- [ ] `./gradlew test --tests "*.CodeFlowToolTest"` PASS
- [ ] `./gradlew test --tests "*.CallGraphIndexAgentTest"` PASS
- [ ] `./gradlew test` 전체 PASS
- [ ] `./scripts/setup-callgraph.sh <clone-path> <db-path>` 실행 후 init.gradle.kts 생성 확인
- [ ] Kurly Android 클론에서 `./gradlew compileDebugKotlin` 실행 시 `call_graph.db` 생성 확인
- [ ] Slack에서 "loadProduct 어디서 불려?" → findCallers 응답 확인
