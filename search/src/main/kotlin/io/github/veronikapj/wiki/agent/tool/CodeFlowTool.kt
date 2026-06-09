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
                    if (e.callerFile.isNotBlank()) appendLine("  _" + e.callerFile + "_")
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
                    if (file.isNotBlank()) appendLine("  _" + file + "_")
                }
                if (affected.size > 15) appendLine("\n_... 외 ${affected.size - 15}건_")
            }.trim()
        }.getOrElse { "임팩트 분석 중 오류: ${it.message}" }.also { db.close() }
    }
}
