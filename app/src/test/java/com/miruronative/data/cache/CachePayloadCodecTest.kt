package com.miruronative.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePayloadCodecTest {
    @Test
    fun largeEpisodeCatalogIsCompressedAndRoundTrips() {
        val catalog = buildString {
            append("{\"providers\":[")
            repeat(12_000) { episode ->
                append("{\"provider\":\"bonk\",\"episode\":")
                append(episode)
                append(",\"title\":\"A repeated episode title\"},")
            }
            append("]}")
        }

        val stored = CachePayloadCodec.encode(catalog)

        assertTrue(stored.startsWith("gzip:"))
        assertTrue(stored.length < catalog.length / 2)
        assertEquals(catalog, CachePayloadCodec.decode(stored))
    }

    @Test
    fun storedPayloadIsSplitIntoCursorWindowSafeParts() {
        val stored = "0123456789abcdef".repeat(60_000)

        val parts = CachePayloadCodec.split(stored)

        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 384 * 1024 })
        assertEquals(stored, parts.joinToString(separator = ""))
    }

    @Test
    fun smallValuesRemainReadableWithoutCompression() {
        val value = "{\"ok\":true}"

        val stored = CachePayloadCodec.encode(value)

        assertFalse(stored.startsWith("gzip:"))
        assertEquals(value, CachePayloadCodec.decode(stored))
    }

    @Test
    fun batchTreePreparationKeepsLargePayloadChunksBehindOneRootMarker() {
        val stored = "0123456789abcdef".repeat(60_000)
        val entry = CacheEntry(
            key = "episodes:1",
            payload = stored,
            createdAt = 10L,
            expiresAt = 20L,
            lastAccessedAt = 10L,
        )

        val tree = prepareCacheTree(entry)
        val root = tree.diskEntries.last()
        val chunks = tree.diskEntries.dropLast(1)

        assertEquals(entry, tree.memoryEntry)
        assertEquals("$CACHE_CHUNK_MARKER${chunks.size}", root.payload)
        assertEquals(entry.key, root.key)
        assertTrue(chunks.all { it.key.startsWith(cacheChunkPrefix(entry.key)) })
        assertEquals(stored, chunks.joinToString(separator = "") { it.payload })
    }

    @Test
    fun batchLockStripesAreUniqueAndGloballyOrdered() {
        val indices = cacheStripeIndices(
            keys = listOf("series:1", "series:2", "series:1", "series:99"),
            stripeCount = 64,
        )

        assertEquals(indices.distinct(), indices)
        assertEquals(indices.sorted(), indices)
    }
}
