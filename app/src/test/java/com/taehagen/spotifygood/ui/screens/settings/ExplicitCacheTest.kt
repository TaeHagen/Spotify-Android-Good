package com.taehagen.spotifygood.ui.screens.settings

import com.taehagen.spotifygood.data.ResponseCache
import com.taehagen.spotifygood.data.db.ResponseCacheDao
import com.taehagen.spotifygood.data.db.ResponseCacheEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

private class RowsDao : ResponseCacheDao {
    val rows = ConcurrentHashMap<String, ResponseCacheEntity>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun put(entity: ResponseCacheEntity) {
        rows[entity.key] = entity
    }
    override suspend fun prune(olderThan: Long) {
        rows.values.removeIf { it.fetchedAt < olderThan }
    }
    override suspend fun clear() = rows.clear()
    override suspend fun delete(key: String) {
        rows.remove(key)
    }
    override suspend fun markStale(prefix: String) {
        rows.replaceAll { k, v -> if (k.startsWith(prefix)) v.copy(fetchedAt = -kotlin.math.abs(v.fetchedAt)) else v }
    }
    override suspend fun markStaleKey(key: String) {
        rows.computeIfPresent(key) { _, v -> v.copy(fetchedAt = -kotlin.math.abs(v.fetchedAt)) }
    }
    override suspend fun pruneFetchedBefore(olderThan: Long) {
        rows.values.removeIf { kotlin.math.abs(it.fetchedAt) < olderThan }
    }
}

class ExplicitCacheTest {
    @Test
    fun theExplicitFilterMarksCatalogPagesStaleAndKeepsEverything() = runBlocking {
        val dao = RowsDao()
        val cache = ResponseCache(dao, Json)
        var cleared = false
        cache.addClearListener { cleared = true }
        val keys = listOf("catalog.home", "catalog.album:spotify:album:a", "catalog.playlist:spotify:playlist:p:100", "library.playlists")
        keys.forEach { dao.put(ResponseCacheEntity(it, "{}", fetchedAt = 1_000L)) }

        invalidatePlayableCatalog(cache)

        // Kept for offline display, but stale: refetched (new `playable` flags) when shown online.
        assertEquals(keys.toSet(), dao.rows.keys)
        listOf("catalog.home", "catalog.album:spotify:album:a", "catalog.playlist:spotify:playlist:p:100").forEach {
            assertTrue("$it stale", dao.rows.getValue(it).fetchedAt < 0)
        }
        // Library lists carry no track `playable` flags: untouched; per-account state not dropped.
        assertEquals(1_000L, dao.rows.getValue("library.playlists").fetchedAt)
        assertFalse(cleared)
    }
}
