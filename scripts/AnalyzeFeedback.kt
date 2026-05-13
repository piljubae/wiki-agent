import java.sql.DriverManager

fun main() {
    val dbPath = ".wiki/feedback.db"
    val url = "jdbc:sqlite:$dbPath"

    try {
        DriverManager.getConnection(url).use { conn ->
            println("--- Feedback Analysis Report ---")
            
            // 1. 전체 통계
            val totalQuery = "SELECT count(*) FROM feedback"
            val total = conn.createStatement().executeQuery(totalQuery).getInt(1)
            println("Total queries logged: $total\n")

            // 2. 응답 타입별 분포
            println("Response Type Distribution:")
            val typeQuery = "SELECT response_type, count(*) as cnt FROM feedback GROUP BY response_type ORDER BY cnt DESC"
            val typeRs = conn.createStatement().executeQuery(typeQuery)
            while (typeRs.next()) {
                println("- ${typeRs.getString("response_type")}: ${typeRs.getInt("cnt")} queries")
            }
            println()

            // 3. RAG vs 단순 검색 통계
            println("RAG Usage:")
            val ragQuery = "SELECT is_rag, count(*) as cnt FROM feedback GROUP BY is_rag"
            val ragRs = conn.createStatement().executeQuery(ragQuery)
            while (ragRs.next()) {
                val isRag = if (ragRs.getInt("is_rag") == 1) "RAG" else "Simple Search"
                println("- $isRag: ${ragRs.getInt("cnt")} queries")
            }
            println()

            // 4. 피드백 만족도 (리액션 있는 것만)
            println("Feedback Satisfaction (for queries with reactions):")
            val feedbackQuery = """
                SELECT reaction, count(*) as cnt 
                FROM feedback 
                WHERE reaction IS NOT NULL 
                GROUP BY reaction
            """.trimIndent()
            val fbRs = conn.createStatement().executeQuery(feedbackQuery)
            while (fbRs.next()) {
                println("- Reaction ${fbRs.getString("reaction")}: ${fbRs.getInt("cnt")} times")
            }
        }
    } catch (e: Exception) {
        println("Error connecting to database: ${e.message}")
        e.printStackTrace()
    }
}
