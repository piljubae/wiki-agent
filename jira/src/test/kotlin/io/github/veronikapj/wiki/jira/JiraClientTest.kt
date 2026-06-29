package io.github.veronikapj.wiki.jira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraClientTest {
    private val client = JiraClient("https://kurly0521.atlassian.net", "dGVzdA==")

    private val issueJson = """
        {"key":"KMA-7275","fields":{
          "summary":"배너 DSP Phase2",
          "status":{"name":"In Progress"},
          "issuetype":{"name":"Story"},
          "assignee":{"displayName":"pilju.bae"},
          "description":"기획 상세입니다. https://kurly0521.atlassian.net/wiki/spaces/PA/pages/12345/Spec"
        }}
    """.trimIndent()

    @Test
    fun `parseIssue maps fields`() {
        val issue = client.parseIssue("KMA-7275", issueJson, "", "")
        assertEquals("KMA-7275", issue.key)
        assertEquals("배너 DSP Phase2", issue.summary)
        assertEquals("In Progress", issue.status)
        assertEquals("Story", issue.type)
        assertEquals("pilju.bae", issue.assignee)
        assertTrue(issue.description.contains("기획 상세"))
    }

    @Test
    fun `parseIssue with null assignee yields empty string`() {
        val json = """{"key":"K-1","fields":{"summary":"s","status":{"name":"To Do"},"issuetype":{"name":"Bug"},"assignee":null,"description":null}}"""
        val issue = client.parseIssue("K-1", json, "", "")
        assertEquals("", issue.assignee)
        assertEquals("", issue.description)
    }

    @Test
    fun `parseComments takes recent comments as author colon body`() {
        val commentsJson = """
            {"comments":[
              {"author":{"displayName":"kim"},"body":"첫 코멘트"},
              {"author":{"displayName":"lee"},"body":"둘째"}
            ]}
        """.trimIndent()
        val comments = client.parseComments(commentsJson)
        assertEquals(2, comments.size)
        assertEquals("kim: 첫 코멘트", comments[0])
    }

    @Test
    fun `extractConfluenceRefs finds page id in description`() {
        val refs = client.extractConfluenceRefs(
            "참고 https://kurly0521.atlassian.net/wiki/spaces/PA/pages/12345/Spec 끝", "")
        assertEquals(1, refs.size)
        assertEquals("12345", refs[0].pageId)
    }

    @Test
    fun `extractConfluenceRefs reads remote links and dedups by pageId`() {
        val remoteJson = """
            [
              {"object":{"url":"https://kurly0521.atlassian.net/wiki/spaces/PA/pages/999/Plan","title":"기획서"}},
              {"object":{"url":"https://github.com/o/r/pull/1","title":"PR"}}
            ]
        """.trimIndent()
        // description에도 999가 있으면 중복 제거되어 1개
        val refs = client.extractConfluenceRefs(
            "https://kurly0521.atlassian.net/wiki/x/pages/999/a", remoteJson)
        assertEquals(1, refs.size)
        assertEquals("999", refs[0].pageId)
        // remote link 제목이 반영됨
        assertEquals("기획서", refs.first { it.pageId == "999" }.title)
    }
}
