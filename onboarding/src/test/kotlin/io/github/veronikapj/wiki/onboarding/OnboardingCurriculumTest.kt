package io.github.veronikapj.wiki.onboarding

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingCurriculumTest {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Test
    fun `space 필드가 파싱된다`() {
        val cur = yaml.decodeFromString(OnboardingCurriculum.serializer(), """
            lastUpdated: "2026-06-05"
            space: "ProductApp"
            phases: []
        """.trimIndent())
        assertEquals("ProductApp", cur.space)
    }

    @Test
    fun `space 누락 시 null이다`() {
        val cur = yaml.decodeFromString(OnboardingCurriculum.serializer(), """
            lastUpdated: "2026-06-05"
            phases: []
        """.trimIndent())
        assertNull(cur.space)
    }
}
