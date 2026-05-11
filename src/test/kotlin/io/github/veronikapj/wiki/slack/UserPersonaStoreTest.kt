package io.github.veronikapj.wiki.slack

import io.github.veronikapj.wiki.config.PersonaType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserPersonaStoreTest {

    private fun createTempStore(): UserPersonaStore {
        val tmp = File.createTempFile("user-personas", ".json").also { it.deleteOnExit() }
        return UserPersonaStore(tmp.absolutePath)
    }

    @Test
    fun `get returns null for unknown user`() {
        val store = createTempStore()
        assertNull(store.get("U_UNKNOWN"))
    }

    @Test
    fun `set and get round-trip`() {
        val store = createTempStore()
        store.set("U123", PersonaType.MZ_INTERN)
        assertEquals(PersonaType.MZ_INTERN, store.get("U123"))
    }

    @Test
    fun `different users are isolated`() {
        val store = createTempStore()
        store.set("U1", PersonaType.BURNOUT)
        store.set("U2", PersonaType.SIGMA)
        assertEquals(PersonaType.BURNOUT, store.get("U1"))
        assertEquals(PersonaType.SIGMA, store.get("U2"))
    }

    @Test
    fun `persists to file and reloads`() {
        val tmp = File.createTempFile("user-personas-persist", ".json").also { it.deleteOnExit() }
        val store1 = UserPersonaStore(tmp.absolutePath)
        store1.set("U999", PersonaType.STARTUP)

        val store2 = UserPersonaStore(tmp.absolutePath)
        assertEquals(PersonaType.STARTUP, store2.get("U999"))
    }
}
