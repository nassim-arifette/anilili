package com.miruronative.data.remote

import android.content.Context
import com.miruronative.data.cache.AppCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class CachedMapping(
    val anilistId: Int?,
)

@Serializable
private data class KonohaMappingResponse(
    val anilist_id: Int,
)

class KonohaClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: kotlinx.serialization.json.Json,
    private val cache: AppCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            runCatching { seedIfNeeded() }
        }
    }

    /**
     * Resolves an AniList ID from a MyAnimeList ID using Konoha's sharded mappings.
     * Caches results (including 404 misses) to avoid hammering the CDN.
     */
    suspend fun resolveAnilistIdFromMal(malId: Int): Int? = withContext(Dispatchers.IO) {
        val cacheKey = "konohamal:v1:$malId"
        
        val cached = cache.getOrFetch(
            key = cacheKey,
            serializer = CachedMapping.serializer(),
            ttlMs = TTL_HIT_MS,
        ) {
            val shard = malId / 1000
            val url = "https://raw.githubusercontent.com/AlokRepo/Konoha/main/data/mappings/mal/$shard/$malId.json"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
                
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        CachedMapping(null)
                    } else if (!response.isSuccessful) {
                        error("Konoha HTTP ${response.code}")
                    } else {
                        val body = response.body?.string().orEmpty()
                        val parsed = json.decodeFromString(KonohaMappingResponse.serializer(), body)
                        CachedMapping(parsed.anilist_id)
                    }
                }
            }.getOrElse {
                // Throwing the exception allows AppCache to use stale cache
                // if it exists, instead of caching a transient network error.
                throw it
            }
        }
        
        cached.anilistId
    }

    private suspend fun seedIfNeeded() {
        val seedKey = "konohaseed:v1"
        if (cache.hasKey(seedKey)) return
        
        try {
            val start = System.currentTimeMillis()
            
            // Read bundled id-map.json from assets
            val inputStream = context.assets.open("id-map.json")
            val rawText = inputStream.bufferedReader().use { it.readText() }
            
            // Parse mapping tree
            val root = json.parseToJsonElement(rawText).jsonObject
            val batch = mutableMapOf<String, String>()
            
            root.forEach { (anilistIdStr, element) ->
                val anilistId = anilistIdStr.toIntOrNull() ?: return@forEach
                val malId = element.jsonObject["mal"]?.jsonPrimitive?.intOrNull ?: return@forEach
                
                val cacheKey = "konohamal:v1:$malId"
                val mappingJson = json.encodeToString(CachedMapping.serializer(), CachedMapping(anilistId))
                batch[cacheKey] = mappingJson
            }
            
            if (batch.isNotEmpty()) {
                cache.putBatch(batch, TTL_HIT_MS)
            }
            
            // Mark seed as completed successfully
            cache.putBatch(mapOf(seedKey to "true"), TTL_HIT_MS)
            val duration = System.currentTimeMillis() - start
            com.miruronative.diagnostics.DiagnosticsLog.event(
                "Konoha database seeded from assets in ${duration}ms, mapped ${batch.size} items"
            )
        } catch (e: Exception) {
            com.miruronative.diagnostics.DiagnosticsLog.throwable("Failed to seed Konoha database from assets", e)
        }
    }

    private companion object {
        const val TTL_HIT_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }
}
