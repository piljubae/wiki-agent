package io.github.veronikapj.callgraph

import java.sql.DriverManager

data class CallEdge(val callerFqn: String, val calleeFqn: String, val callerFile: String)

class CallGraphDb(private val dbPath: String) {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { c ->
        c.autoCommit = false
        c.createStatement().executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS call_edges (
                caller_fqn TEXT NOT NULL,
                callee_fqn TEXT NOT NULL,
                caller_file TEXT,
                PRIMARY KEY (caller_fqn, callee_fqn)
            )
            """.trimIndent()
        )
        c.createStatement().executeUpdate(
            "CREATE INDEX IF NOT EXISTS idx_callee ON call_edges(callee_fqn)"
        )
        c.createStatement().executeUpdate(
            "CREATE INDEX IF NOT EXISTS idx_caller ON call_edges(caller_fqn)"
        )
        c.commit()
    }

    fun upsertEdge(callerFqn: String, calleeFqn: String, callerFile: String) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO call_edges(caller_fqn, callee_fqn, caller_file) VALUES(?,?,?)"
        ).use {
            it.setString(1, callerFqn)
            it.setString(2, calleeFqn)
            it.setString(3, callerFile)
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
        while (rs.next()) {
            result += CallEdge(rs.getString(1), rs.getString(2), rs.getString(3) ?: "")
        }
        return result
    }

    fun close() = conn.close()
}
