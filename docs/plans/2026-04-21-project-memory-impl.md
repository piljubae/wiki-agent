# 프로젝트 메모리 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** `/wiki memory` 커맨드로 프로젝트 특성을 저장하고 시스템 프롬프트에 주입하여 검색/답변 품질을 높인다.

**Architecture:** `ProjectMemory`가 `.wiki/memory.md` 파일을 관리한다. `SlackConfigHandler`가 `/wiki memory` 커맨드를 파싱하여 ProjectMemory를 호출한다. `OrchestratorAgent`가 메모리를 시스템 프롬프트에 주입한다.

**Tech Stack:** Kotlin, File I/O

---

## Task 1: ProjectMemory 클래스

**Files:**
- Create: `src/main/kotlin/io/github/veronikapj/wiki/context/ProjectMemory.kt`
- Test: `src/test/kotlin/io/github/veronikapj/wiki/context/ProjectMemoryTest.kt`

**Step 1: 테스트 작성**

```kotlin
package io.github.veronikapj.wiki.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class ProjectMemoryTest {

    private fun createTempMemory(): ProjectMemory {
        val file = File(System.getProperty("java.io.tmpdir"), "wiki-test-memory-${System.nanoTime()}.md")
        return ProjectMemory(file.absolutePath)
    }

    @Test
    fun `load returns null when no file`() {
        val memory = createTempMemory()
        assertNull(memory.load())
    }

    @Test
    fun `add and load returns content`() {
        val memory = createTempMemory()
        memory.add("이 프로젝트는 Spring Boot 3.x 기반")
        memory.add("DB는 PostgreSQL 사용")
        val content = memory.load()
        assertTrue(content!!.contains("Spring Boot 3.x"))
        assertTrue(content.contains("PostgreSQL"))
    }

    @Test
    fun `show returns formatted message`() {
        val memory = createTempMemory()
        memory.add("항목1")
        memory.add("항목2")
        val show = memory.show()
        assertTrue(show.contains("항목1"))
        assertTrue(show.contains("항목2"))
    }

    @Test
    fun `show returns empty message when no memory`() {
        val memory = createTempMemory()
        val show = memory.show()
        assertTrue(show.contains("저장된 메모리가 없습니다"))
    }

    @Test
    fun `clear removes all content`() {
        val memory = createTempMemory()
        memory.add("항목1")
        memory.clear()
        assertNull(memory.load())
    }
}
```

**Step 2: 테스트 실행 — FAIL**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ProjectMemoryTest" 2>&1 | tail -10
```

**Step 3: 구현**

```kotlin
package io.github.veronikapj.wiki.context

import java.io.File

class ProjectMemory(private val filePath: String = ".wiki/memory.md") {

    fun load(): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        return file.readText().ifBlank { null }
    }

    fun add(content: String) {
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.appendText("- $content\n")
    }

    fun show(): String {
        val content = load() ?: return "저장된 메모리가 없습니다."
        return "📝 프로젝트 메모리:\n$content"
    }

    fun clear() {
        val file = File(filePath)
        if (file.exists()) file.delete()
    }
}
```

**Step 4: 테스트 실행 — PASS**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.ProjectMemoryTest" 2>&1 | tail -10
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/context/ProjectMemory.kt \
        src/test/kotlin/io/github/veronikapj/wiki/context/ProjectMemoryTest.kt
git commit -m "feat: ProjectMemory — .wiki/memory.md 기반 프로젝트 메모리"
```

---

## Task 2: SlackConfigHandler에 /wiki memory 커맨드 추가

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt`
- Modify: `src/test/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandlerTest.kt`

**Step 1: 테스트 추가**

`SlackConfigHandlerTest.kt`에 추가 (기존 테스트 패턴을 따름):

```kotlin
@Test
fun `memory add stores content`() {
    val memory = io.github.veronikapj.wiki.context.ProjectMemory(
        java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
    )
    val handler = SlackConfigHandler(config = defaultConfig(), projectMemory = memory)
    val result = handler.handle("/wiki memory add Spring Boot 3.x 기반")
    assertTrue(result.contains("저장 완료"))
    assertTrue(memory.load()!!.contains("Spring Boot 3.x"))
}

@Test
fun `memory show returns content`() {
    val memory = io.github.veronikapj.wiki.context.ProjectMemory(
        java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
    )
    memory.add("테스트 항목")
    val handler = SlackConfigHandler(config = defaultConfig(), projectMemory = memory)
    val result = handler.handle("/wiki memory show")
    assertTrue(result.contains("테스트 항목"))
}

@Test
fun `memory clear removes content`() {
    val memory = io.github.veronikapj.wiki.context.ProjectMemory(
        java.io.File(System.getProperty("java.io.tmpdir"), "wiki-test-mem-${System.nanoTime()}.md").absolutePath
    )
    memory.add("삭제될 항목")
    val handler = SlackConfigHandler(config = defaultConfig(), projectMemory = memory)
    val result = handler.handle("/wiki memory clear")
    assertTrue(result.contains("초기화"))
    assertNull(memory.load())
}
```

NOTE: Check the existing test file to see how `defaultConfig()` or the config is set up. Adapt accordingly. If there's no helper, just create a minimal `WikiConfig()`.

**Step 2: 테스트 실행 — FAIL**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.SlackConfigHandlerTest" 2>&1 | tail -10
```

**Step 3: SlackConfigHandler 수정**

Add `projectMemory` parameter to constructor:

```kotlin
class SlackConfigHandler(
    private var config: WikiConfig,
    private val configPath: String = ".wikiq/config.yml",
    private val persistOnChange: Boolean = false,
    private val onReindex: (suspend () -> Int)? = null,
    private val projectMemory: ProjectMemory? = null,  // NEW
)
```

Add import: `import io.github.veronikapj.wiki.context.ProjectMemory`

Add memory handling to `handle()`:

```kotlin
fun handle(command: String): String {
    val parts = command.trim().split(" ")
    return when {
        parts.size >= 2 && parts[1] == "memory" -> handleMemory(parts.drop(2))
        parts.size >= 3 && parts[1] == "config" && parts[2] == "space" -> {
            // ... existing code
        }
        // ... rest same
    }
}

private fun handleMemory(args: List<String>): String {
    val mem = projectMemory ?: return "프로젝트 메모리가 비활성화 상태입니다."
    val subcommand = args.firstOrNull()
    return when (subcommand) {
        "add" -> {
            val content = args.drop(1).joinToString(" ").trim()
            if (content.isBlank()) return "사용법: /wiki memory add <내용>"
            mem.add(content)
            "메모리 저장 완료: $content"
        }
        "show" -> mem.show()
        "clear" -> {
            mem.clear()
            "프로젝트 메모리 초기화 완료"
        }
        else -> "사용법: /wiki memory add|show|clear"
    }
}
```

Update `helpMessage()` to include memory commands:

```kotlin
private fun helpMessage() = """
    사용법:
    • `/wiki <질문>` — Confluence에서 검색
    • `/wiki config space DEV,PM,HR` — 검색 스페이스 설정
    • `/wiki config space show` — 현재 설정 확인
    • `/wiki memory add <내용>` — 프로젝트 정보 저장
    • `/wiki memory show` — 저장된 프로젝트 정보 확인
    • `/wiki memory clear` — 프로젝트 정보 초기화
    • `/wiki reindex` — RAG 재인덱싱
    • `/wiki reindex status` — 마지막 인덱싱 정보
""".trimIndent()
```

**Step 4: 테스트 실행 — PASS**

```bash
cd /tmp/wiki-agent
./gradlew test --tests "*.SlackConfigHandlerTest" 2>&1 | tail -10
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandler.kt \
        src/test/kotlin/io/github/veronikapj/wiki/slack/SlackConfigHandlerTest.kt
git commit -m "feat: /wiki memory add|show|clear 커맨드"
```

---

## Task 3: OrchestratorAgent + Main.kt 와이어링

**Files:**
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt`
- Modify: `src/main/kotlin/io/github/veronikapj/wiki/Main.kt`

**Step 1: OrchestratorAgent에 projectMemory 추가**

Constructor에 파라미터 추가:

```kotlin
import io.github.veronikapj.wiki.context.ProjectMemory

class OrchestratorAgent(
    // ... existing params ...
    private val conversationStore: ConversationStore? = null,
    private val projectMemory: ProjectMemory? = null,  // NEW
)
```

`answerWithKoogAgent()`에서 메모리 로드 + buildAgent에 전달:

```kotlin
// After loading summary, before the model loop:
val memory = projectMemory?.load()

// Pass to buildAgent:
val result = runCatching { buildAgent(model, listener, conversationHistory, summary, memory).run(question) }
```

`buildAgent()`에 memory 파라미터 추가:

```kotlin
private fun buildAgent(
    model: LLModel,
    listener: SearchProgressListener? = null,
    history: List<Turn> = emptyList(),
    summary: String? = null,
    memory: String? = null,  // NEW
): AIAgent<String, String> {
    val systemPrompt = buildString {
        // ... existing sources + rules ...
        
        // Project memory (before summary)
        memory?.let {
            appendLine()
            appendLine("# 프로젝트 정보")
            appendLine(it)
        }
        
        // Summary (existing)
        summary?.let {
            appendLine()
            appendLine("# 이전 대화 요약")
            appendLine(it)
        }
    }
    // ... rest same
}
```

**Step 2: Main.kt 와이어링**

```kotlin
import io.github.veronikapj.wiki.context.ProjectMemory

// After conversationStore creation:
val projectMemory = ProjectMemory()

// OrchestratorAgent constructor:
val orchestrator = OrchestratorAgent(
    // ... existing params ...
    conversationStore = conversationStore,
    projectMemory = projectMemory,
)

// SlackConfigHandler constructor:
val configHandler = SlackConfigHandler(
    config = config,
    persistOnChange = true,
    onReindex = vectorIndexAgent?.let { agent -> { agent.indexAll() } },
    projectMemory = projectMemory,
)
```

**Step 3: .gitignore**

`.wiki/memory.md`를 gitignore에 추가:

```bash
echo ".wiki/memory.md" >> .gitignore
```

**Step 4: 컴파일 + 테스트**

```bash
cd /tmp/wiki-agent
./gradlew compileKotlin 2>&1 | tail -10
./gradlew test 2>&1 | tail -15
```

**Step 5: 커밋**

```bash
cd /tmp/wiki-agent
git add src/main/kotlin/io/github/veronikapj/wiki/agent/OrchestratorAgent.kt \
        src/main/kotlin/io/github/veronikapj/wiki/Main.kt \
        .gitignore
git commit -m "feat: 프로젝트 메모리 와이어링 — OrchestratorAgent + Main.kt"
```
