package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.api.PlayerStorage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** In-memory [PlayerStorage] so the store is testable without Bukkit. */
private class FakePlayerStorage : PlayerStorage {
    val map = mutableMapOf<String, Any>()
    override fun getString(key: String): String? = map[key] as? String
    override fun setString(key: String, value: String) { map[key] = value }
    override fun getInt(key: String): Int? = map[key] as? Int
    override fun setInt(key: String, value: Int) { map[key] = value }
    override fun getBoolean(key: String): Boolean? = map[key] as? Boolean
    override fun setBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getLong(key: String): Long? = map[key] as? Long
    override fun setLong(key: String, value: Long) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun has(key: String): Boolean = map.containsKey(key)
}

class CheckoutStoreTest {

    private val storage = FakePlayerStorage()
    private val store = CheckoutStore(storage)

    @Test
    fun `returns null when nothing recorded`() {
        assertNull(store.get())
    }

    @Test
    fun `roundtrips a full checkout`() {
        val checkout = Checkout(schematicId = "s-1", versionId = "v-9", branchId = "b-2")
        store.set(checkout)
        assertEquals(checkout, store.get())
    }

    @Test
    fun `roundtrips a checkout without version info`() {
        val checkout = Checkout(schematicId = "s-1", versionId = null, branchId = null)
        store.set(checkout)
        assertEquals(checkout, store.get())
    }

    @Test
    fun `clear removes the stored checkout`() {
        store.set(Checkout("s-1", "v-1", "b-1"))
        store.clear()
        assertNull(store.get())
        assertFalse(storage.has(CheckoutStore.STORAGE_KEY))
    }

    @Test
    fun `malformed stored payload reads as null instead of throwing`() {
        storage.setString(CheckoutStore.STORAGE_KEY, "{not json")
        assertNull(store.get())
        storage.setString(CheckoutStore.STORAGE_KEY, """{"versionId":"v-1"}""") // no schematicId
        assertNull(store.get())
    }

    @Test
    fun `set overwrites the previous checkout`() {
        store.set(Checkout("s-1", "v-1", "b-1"))
        store.set(Checkout("s-2", null, null))
        assertEquals(Checkout("s-2", null, null), store.get())
        assertTrue(storage.has(CheckoutStore.STORAGE_KEY))
    }
}
