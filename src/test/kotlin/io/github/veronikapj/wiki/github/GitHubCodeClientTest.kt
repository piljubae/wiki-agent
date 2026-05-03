package io.github.veronikapj.wiki.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class GitHubCodeClientTest {

    private val client = GitHubCodeClient(token = "")

    @Test
    fun `parsePrUrl — github PR URL에서 repo와 PR 번호 추출`() {
        val result = client.parsePrUrl("https://github.com/thefarmersfront/kurly-android/pull/7400")
        assertNotNull(result)
        assertEquals("thefarmersfront/kurly-android", result.first)
        assertEquals(7400, result.second)
    }

    @Test
    fun `parsePrUrl — PR URL이 아니면 null 반환`() {
        assertNull(client.parsePrUrl("https://github.com/owner/repo"))
        assertNull(client.parsePrUrl("https://github.com/owner/repo/issues/123"))
        assertNull(client.parsePrUrl("https://example.com/pull/1"))
    }

    @Test
    fun `filterDiffLines — lock 파일과 generated 파일 diff 제거`() {
        val diff = """
            diff --git a/Podfile.lock b/Podfile.lock
            +some lock content
            diff --git a/src/Main.kt b/src/Main.kt
            +real code change
            diff --git a/build/generated/Main.kt b/build/generated/Main.kt
            +generated content
        """.trimIndent()
        val result = client.filterDiffLines(diff)
        assert(!result.contains("Podfile.lock"))
        assert(!result.contains("generated"))
        assert(result.contains("src/Main.kt"))
    }

    @Test
    fun `extractTicket — 브랜치명 또는 PR 제목에서 KMA-XXXX 추출`() {
        assertEquals("KMA-7275", client.extractTicket("KMA-7275 배너 DSP Phase2", "feature/KMA-7275"))
        assertEquals("KMA-7275", client.extractTicket("배너 수정", "feature/KMA-7275-banner"))
        assertEquals("IUHG-123", client.extractTicket("IUHG-123 growth feature", ""))
        assertNull(client.extractTicket("일반 커밋", "main"))
    }

    @Test
    fun `parsePrListJson — 중첩 JSON에서 PR 목록 파싱`() {
        val json = """
            [
              {
                "number": 101,
                "title": "KMA-1234 feature",
                "state": "closed",
                "merged_at": "2026-05-01T10:00:00Z",
                "user": { "login": "dev1", "id": 1 },
                "head": { "ref": "feature/KMA-1234", "sha": "abc" },
                "base": { "ref": "develop", "sha": "def" }
              },
              {
                "number": 102,
                "title": "fix typo",
                "state": "open",
                "merged_at": null,
                "user": { "login": "dev2", "id": 2 },
                "head": { "ref": "fix/typo", "sha": "ghi" },
                "base": { "ref": "develop", "sha": "jkl" }
              }
            ]
        """.trimIndent()

        val results = client.parsePrListJson("owner/repo", json)
        assertEquals(2, results.size)
        assertEquals(101, results[0].number)
        assertEquals("KMA-1234 feature", results[0].title)
        assertEquals("dev1", results[0].author)
        assertEquals(102, results[1].number)
    }
}
