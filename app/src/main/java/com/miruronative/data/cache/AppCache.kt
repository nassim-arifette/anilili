package com.miruronative.data.cache

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.miruronative.diagnostics.DiagnosticsLog
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "cache_entries",
    indices = [Index("expiresAt"), Index("lastAccessedAt")],
)
data class CacheEntry(
    @PrimaryKey val key: String,
    val payload: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastAccessedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache_entries WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entries: List<CacheEntry>)

    @Query("UPDATE cache_entries SET lastAccessedAt = :now WHERE `key` = :key OR instr(`key`, :chunkPrefix) = 1")
    suspend fun touchTree(key: String, chunkPrefix: String, now: Long)

    @Query("DELETE FROM cache_entries WHERE `key` = :key OR instr(`key`, :chunkPrefix) = 1")
    suspend fun deleteTree(key: String, chunkPrefix: String)

    @Query("DELETE FROM cache_entries WHERE expiresAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM cache_entries WHERE instr(`key`, :chunkSeparator) = 0")
    suspend fun rootCount(chunkSeparator: String): Int

    @Query(
        "SELECT `key` FROM cache_entries WHERE instr(`key`, :chunkSeparator) = 0 " +
            "ORDER BY lastAccessedAt ASC LIMIT :count",
    )
    suspend fun leastRecentlyUsedRoots(chunkSeparator: String, count: Int): List<String>
}

@Database(entities = [CacheEntry::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

/**
 * Bounded two-level cache used for public metadata and episode catalogs.
 *
 * Fresh entries return immediately. Expired-but-usable entries implement stale-while-revalidate:
 * the caller gets the last successful value while one coalesced refresh happens in the background.
 * Stream URLs are deliberately not stored here because provider signatures often expire quickly.
 */
class AppCache(
    context: Context,
    private val json: Json,
) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        CacheDatabase::class.java,
        "anilili-cache.db",
    ).fallbackToDestructiveMigration(true).build()
    private val dao = database.cacheDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyLocks = Array(64) { Mutex() }
    private val memory = object : LinkedHashMap<String, CacheEntry>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MEMORY_ENTRIES
    }

    suspend fun <T> getOrFetch(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        staleForMs: Long = DEFAULT_STALE_MS,
        forceRefresh: Boolean = false,
        fetch: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        val cached = read(key)
        val decoded = cached?.let { decode(it, serializer) }
        if (!forceRefresh && cached != null && decoded != null && now <= cached.expiresAt) {
            touch(cached, now)
            return decoded
        }
        if (!forceRefresh && cached != null && decoded != null && now <= cached.expiresAt + staleForMs) {
            touch(cached, now)
            scope.launch {
                try {
                    refresh(key, serializer, ttlMs, false, fetch)
                } catch (_: Exception) {
                    // The stale value remains available until its safety window closes.
                }
            }
            return decoded
        }
        return try {
            refresh(key, serializer, ttlMs, forceRefresh, fetch)
        } catch (e: Exception) {
            // Last-known-good fallback: an expired entry beats an error screen when the
            // network or an upstream (e.g. Cloudflare in front of AniList) is refusing us.
            if (e is kotlinx.coroutines.CancellationException) throw e
            decoded ?: throw e
        }
    }

    suspend fun hasKey(key: String): Boolean {
        return read(key) != null
    }

    /** Fresh-only read: the decoded value when [key] exists and hasn't expired, else null. */
    suspend fun <T> getIfFresh(key: String, serializer: KSerializer<T>): T? {
        val now = System.currentTimeMillis()
        val entry = read(key) ?: return null
        if (now > entry.expiresAt) return null
        val value = decode(entry, serializer) ?: return null
        touch(entry, now)
        return value
    }

    suspend fun putBatch(entries: Map<String, String>, ttlMs: Long) {
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val cacheEntries = entries.map { (key, jsonString) ->
            CacheEntry(
                key = key,
                payload = CachePayloadCodec.encode(jsonString),
                createdAt = now,
                expiresAt = now + ttlMs,
                lastAccessedAt = now,
            )
        }
        val preparedTrees = cacheEntries.map(::prepareCacheTree)
        withKeyLocks(cacheEntries.map(CacheEntry::key)) {
            database.withTransaction {
                preparedTrees.forEach { tree ->
                    dao.deleteTree(tree.memoryEntry.key, cacheChunkPrefix(tree.memoryEntry.key))
                }
                dao.putAll(preparedTrees.flatMap(PreparedCacheTree::diskEntries))
            }
            // A failed transaction leaves both levels untouched. Publish the new memory snapshot
            // only after every root and chunk is durably visible together.
            synchronized(memory) {
                preparedTrees.forEach { tree -> memory[tree.memoryEntry.key] = tree.memoryEntry }
            }
            prune(now)
        }
    }

    private suspend fun <T> refresh(
        key: String,
        serializer: KSerializer<T>,
        ttlMs: Long,
        forceRefresh: Boolean,
        fetch: suspend () -> T,
    ): T {
        val lock = keyLocks[cacheStripeIndex(key, keyLocks.size)]
        return lock.withLock {
            val now = System.currentTimeMillis()
            val newer = read(key)
            if (!forceRefresh && newer != null && now <= newer.expiresAt) {
                decode(newer, serializer)?.let { return@withLock it }
            }

            val value = fetch()
            val payload = CachePayloadCodec.encode(json.encodeToString(serializer, value))
            val entry = CacheEntry(
                key = key,
                payload = payload,
                createdAt = now,
                expiresAt = now + ttlMs,
                lastAccessedAt = now,
            )
            write(entry)
            synchronized(memory) { memory[key] = entry }
            prune(now)
            value
        }
    }

    private suspend fun read(key: String): CacheEntry? {
        synchronized(memory) { memory[key] }?.let { return it }
        return try {
            val entry = database.withTransaction readTransaction@{
                val stored = dao.get(key) ?: return@readTransaction null
                val chunkCount = stored.payload.removePrefix(CACHE_CHUNK_MARKER).toIntOrNull()
                    ?.takeIf { stored.payload.startsWith(CACHE_CHUNK_MARKER) && it > 0 }
                if (chunkCount == null) {
                    stored
                } else {
                    val payload = buildString {
                        for (index in 0 until chunkCount) {
                            val chunk = dao.get(cacheChunkKey(key, index)) ?: run {
                                dao.deleteTree(key, cacheChunkPrefix(key))
                                DiagnosticsLog.event("Cache chunks incomplete key=$key; deleting")
                                return@readTransaction null
                            }
                            append(chunk.payload)
                        }
                    }
                    stored.copy(payload = payload)
                }
            }
            entry ?: return null
            // If a batch committed while this disk read was in flight, its memory entry wins.
            synchronized(memory) { memory[key] ?: entry.also { memory[key] = it } }
        } catch (e: SQLiteBlobTooBigException) {
            DiagnosticsLog.throwable("Cache row exceeded CursorWindow key=$key; deleting", e)
            dao.deleteTree(key, cacheChunkPrefix(key))
            null
        }
    }

    private suspend fun write(entry: CacheEntry) {
        val tree = prepareCacheTree(entry)
        database.withTransaction {
            dao.deleteTree(entry.key, cacheChunkPrefix(entry.key))
            dao.putAll(tree.diskEntries)
        }
    }

    private suspend fun <T> decode(entry: CacheEntry, serializer: KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, CachePayloadCodec.decode(entry.payload))
        } catch (_: Exception) {
            dao.deleteTree(entry.key, cacheChunkPrefix(entry.key))
            synchronized(memory) { memory.remove(entry.key) }
            null
        }

    private fun touch(entry: CacheEntry, now: Long) {
        val touched = entry.copy(lastAccessedAt = now)
        synchronized(memory) { memory[entry.key] = touched }
        scope.launch { dao.touchTree(entry.key, cacheChunkPrefix(entry.key), now) }
    }

    private suspend fun prune(now: Long) {
        database.withTransaction {
            dao.deleteOlderThan(now - DISK_STALE_RETENTION_MS)
            val overflow = dao.rootCount(CACHE_CHUNK_SEPARATOR) - DISK_ENTRIES
            if (overflow > 0) {
                dao.leastRecentlyUsedRoots(CACHE_CHUNK_SEPARATOR, overflow).forEach { key ->
                    dao.deleteTree(key, cacheChunkPrefix(key))
                }
            }
        }
    }

    private suspend fun <T> withKeyLocks(keys: Collection<String>, block: suspend () -> T): T {
        val locks = cacheStripeIndices(keys, keyLocks.size).map(keyLocks::get)
        return withLocks(locks, index = 0, block)
    }

    private suspend fun <T> withLocks(
        locks: List<Mutex>,
        index: Int,
        block: suspend () -> T,
    ): T = if (index >= locks.size) {
        block()
    } else {
        locks[index].withLock { withLocks(locks, index + 1, block) }
    }

    private companion object {
        const val MEMORY_ENTRIES = 80
        const val DISK_ENTRIES = 500
        const val DEFAULT_STALE_MS = 7L * 24 * 60 * 60 * 1000
        const val DISK_STALE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

internal const val CACHE_CHUNK_MARKER = "cache-chunks:"
internal const val CACHE_CHUNK_SEPARATOR = "|chunk|"

internal data class PreparedCacheTree(
    val memoryEntry: CacheEntry,
    val diskEntries: List<CacheEntry>,
)

internal fun prepareCacheTree(entry: CacheEntry): PreparedCacheTree {
    val parts = CachePayloadCodec.split(entry.payload)
    if (parts.size == 1) return PreparedCacheTree(entry, listOf(entry))
    val chunks = parts.mapIndexed { index, payload ->
        entry.copy(key = cacheChunkKey(entry.key, index), payload = payload)
    }
    return PreparedCacheTree(
        memoryEntry = entry,
        diskEntries = chunks + entry.copy(payload = "$CACHE_CHUNK_MARKER${parts.size}"),
    )
}

internal fun cacheChunkPrefix(key: String): String = "$key$CACHE_CHUNK_SEPARATOR"

internal fun cacheChunkKey(key: String, index: Int): String = "${cacheChunkPrefix(key)}$index"

internal fun cacheStripeIndex(key: String, stripeCount: Int): Int {
    require(stripeCount > 0)
    return (key.hashCode() and Int.MAX_VALUE) % stripeCount
}

internal fun cacheStripeIndices(keys: Collection<String>, stripeCount: Int): List<Int> =
    keys.map { cacheStripeIndex(it, stripeCount) }.distinct().sorted()
