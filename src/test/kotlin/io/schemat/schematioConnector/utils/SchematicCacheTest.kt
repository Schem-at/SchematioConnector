package io.schemat.schematioConnector.utils

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for SchematicCache caching utilities.
 */
@DisplayName("SchematicCache")
class SchematicCacheTest {

    private lateinit var cache: SchematicCache

    @BeforeEach
    fun setup() {
        // Create cache with short TTL for testing
        cache = SchematicCache(ttlMs = 100)
    }

    @Nested
    @DisplayName("getListings / putListings")
    inner class ListingsCacheTests {

        @Test
        fun `returns null for uncached key`() {
            assertNull(cache.getListings("nonexistent"))
        }

        @Test
        fun `returns cached data for valid key`() {
            val schematics = listOf(createSchematic("1", "Castle"))
            val meta = createMeta(1, 1, 1)

            cache.putListings("test-key", schematics, meta)

            val result = cache.getListings("test-key")
            assertNotNull(result)
            assertEquals(1, result!!.schematics.size)
            assertEquals("Castle", result.schematics[0].get("name").asString)
        }

        @Test
        fun `returns null after TTL expires`() {
            val schematics = listOf(createSchematic("1", "Castle"))
            val meta = createMeta(1, 1, 1)

            cache.putListings("test-key", schematics, meta)

            // Wait for TTL to expire
            Thread.sleep(150)

            assertNull(cache.getListings("test-key"))
        }

        @Test
        fun `different keys are independent`() {
            cache.putListings("key1", listOf(createSchematic("1", "Castle")), createMeta(1, 1, 1))
            cache.putListings("key2", listOf(createSchematic("2", "Tower")), createMeta(1, 1, 1))

            val result1 = cache.getListings("key1")
            val result2 = cache.getListings("key2")

            assertNotNull(result1)
            assertNotNull(result2)
            assertEquals("Castle", result1!!.schematics[0].get("name").asString)
            assertEquals("Tower", result2!!.schematics[0].get("name").asString)
        }
    }

    @Nested
    @DisplayName("getListingsStale")
    inner class StaleCacheTests {

        @Test
        fun `returns null for never-cached key`() {
            assertNull(cache.getListingsStale("nonexistent"))
        }

        @Test
        fun `returns data with age for expired entries`() {
            cache.putListings("test-key", listOf(createSchematic("1", "Castle")), createMeta(1, 1, 1))

            // Wait for TTL to expire
            Thread.sleep(150)

            // Stale get should return data even after TTL expired
            // (Note: don't call getListings first as it removes expired entries)
            val staleResult = cache.getListingsStale("test-key")
            assertNotNull(staleResult)

            val (data, ageMs) = staleResult!!
            assertEquals("Castle", data.schematics[0].get("name").asString)
            // Age should be positive (allowing some timing variance)
            assertTrue(ageMs > 0, "Age should be positive but was $ageMs")
        }

        @Test
        fun `getListings removes expired entries`() {
            cache.putListings("test-key", listOf(createSchematic("1", "Castle")), createMeta(1, 1, 1))

            // Wait for TTL to expire
            Thread.sleep(150)

            // Normal get should return null and remove the entry
            assertNull(cache.getListings("test-key"))

            // Now stale get should also return null (entry was removed)
            assertNull(cache.getListingsStale("test-key"))
        }
    }

    @Nested
    @DisplayName("getDetails / putDetails")
    inner class DetailsCacheTests {

        @Test
        fun `returns null for uncached ID`() {
            assertNull(cache.getDetails("nonexistent"))
        }

        @Test
        fun `returns cached details`() {
            val details = createSchematic("abc123", "My Schematic")
            cache.putDetails("abc123", details)

            val result = cache.getDetails("abc123")
            assertNotNull(result)
            assertEquals("My Schematic", result!!.get("name").asString)
        }

        @Test
        fun `expires after TTL`() {
            cache.putDetails("abc123", createSchematic("abc123", "Test"))

            Thread.sleep(150)

            assertNull(cache.getDetails("abc123"))
        }
    }

    @Nested
    @DisplayName("cleanup")
    inner class CleanupTests {

        @Test
        fun `removes expired entries`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))

            Thread.sleep(150)

            val removed = cache.cleanup()
            assertEquals(1, removed)
            assertEquals(0, cache.getListingsCacheSize())
        }

        @Test
        fun `preserves non-expired entries`() {
            // Use longer TTL cache
            val longCache = SchematicCache(ttlMs = 10000)
            longCache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))

            val removed = longCache.cleanup()
            assertEquals(0, removed)
            assertEquals(1, longCache.getListingsCacheSize())
        }
    }

    @Nested
    @DisplayName("clear")
    inner class ClearTests {

        @Test
        fun `removes all entries`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test1")), createMeta(1, 1, 1))
            cache.putListings("key2", listOf(createSchematic("2", "Test2")), createMeta(1, 1, 1))
            cache.putDetails("id1", createSchematic("id1", "Detail"))

            cache.clear()

            assertEquals(0, cache.getListingsCacheSize())
            assertEquals(0, cache.getDetailsCacheSize())
        }

        @Test
        fun `resets statistics`() {
            cache.getListings("miss1")
            cache.getListings("miss2")

            cache.clear()

            val stats = cache.getStats()
            assertEquals(0L, stats["hits"])
            assertEquals(0L, stats["misses"])
        }
    }

    @Nested
    @DisplayName("invalidateListings")
    inner class InvalidateListingsTests {

        @Test
        fun `clears all listings but preserves details`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))
            cache.putDetails("id1", createSchematic("id1", "Detail"))

            cache.invalidateListings()

            assertEquals(0, cache.getListingsCacheSize())
            assertEquals(1, cache.getDetailsCacheSize())
        }
    }

    @Nested
    @DisplayName("getStats")
    inner class StatsTests {

        @Test
        fun `tracks hits and misses`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))

            // One hit
            cache.getListings("key1")
            // Two misses
            cache.getListings("nonexistent1")
            cache.getListings("nonexistent2")

            val stats = cache.getStats()
            assertEquals(1L, stats["hits"])
            assertEquals(2L, stats["misses"])
        }

        @Test
        fun `reports cache sizes`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))
            cache.putDetails("id1", createSchematic("id1", "Detail"))
            cache.putDetails("id2", createSchematic("id2", "Detail2"))

            val stats = cache.getStats()
            assertEquals(1, stats["listingsCached"])
            assertEquals(2, stats["detailsCached"])
        }
    }

    @Nested
    @DisplayName("hasData")
    inner class HasDataTests {

        @Test
        fun `returns false when empty`() {
            assertFalse(cache.hasData())
        }

        @Test
        fun `returns true when listings cached`() {
            cache.putListings("key1", listOf(createSchematic("1", "Test")), createMeta(1, 1, 1))
            assertTrue(cache.hasData())
        }

        @Test
        fun `returns true when details cached`() {
            cache.putDetails("id1", createSchematic("id1", "Detail"))
            assertTrue(cache.hasData())
        }
    }

    // Helper methods
    private fun createSchematic(id: String, name: String): JsonObject {
        return JsonObject().apply {
            addProperty("short_id", id)
            addProperty("name", name)
            addProperty("is_public", true)
        }
    }

    private fun createMeta(currentPage: Int, lastPage: Int, total: Int): JsonObject {
        return JsonObject().apply {
            addProperty("current_page", currentPage)
            addProperty("last_page", lastPage)
            addProperty("total", total)
        }
    }
}
