package com.a.anizm

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * A small in-memory cache with a size cap and a time-to-live. Entries past the TTL read as absent;
 * past the cap, the least recently written entry goes first (never the whole map at once).
 */
internal class TtlCache<K : Any, V : Any>(private val max: Int, private val ttlMs: Long) {
    private class Item<V>(val value: V, val time: Long)
    private val map = LinkedHashMap<K, Item<V>>()

    @Synchronized operator fun get(key: K): V? {
        val it = map[key] ?: return null
        if (System.currentTimeMillis() - it.time > ttlMs) { map.remove(key); return null }
        return it.value
    }
    @Synchronized operator fun set(key: K, value: V) {
        map.remove(key) // re-insert, so insertion order is write order
        map[key] = Item(value, System.currentTimeMillis())
        if (map.size > max) {
            val now = System.currentTimeMillis()
            map.entries.removeAll { now - it.value.time > ttlMs }
            val it = map.entries.iterator()
            while (map.size > max && it.hasNext()) { it.next(); it.remove() }
        }
    }
    /** Milliseconds since [key] was written, or null. */
    @Synchronized fun age(key: K): Long? = map[key]?.let { System.currentTimeMillis() - it.time }
    @Synchronized fun remove(key: K): V? = map.remove(key)?.value
    @Synchronized fun clear() = map.clear()
    @Synchronized fun containsKey(key: K): Boolean = get(key) != null
    val size: Int @Synchronized get() = map.size
}

/**
 * String values kept across app restarts, in one SharedPreferences entry (a JSON object of
 * key -> [time, value]). For facts about one video that never change (its size, where its
 * timestamps jump, which player an id points at), so a second visit costs no requests.
 */
internal class PersistentCache(private val prefs: SharedPreferences?, private val name: String,
                               private val max: Int, private val ttlMs: Long) {
    private class Item(val value: String, val time: Long)
    private var map: LinkedHashMap<String, Item>? = null

    private fun loaded(): LinkedHashMap<String, Item> {
        map?.let { return it }
        val m = LinkedHashMap<String, Item>()
        try {
            val o = JSONObject(prefs?.getString(name, null) ?: "{}")
            val now = System.currentTimeMillis()
            for (k in o.keys()) {
                val a = o.optJSONArray(k) ?: continue
                val t = a.optLong(0)
                if (now - t <= ttlMs) m[k] = Item(a.optString(1), t)
            }
        } catch (_: Exception) {}
        map = m
        return m
    }
    private fun save(m: LinkedHashMap<String, Item>) {
        try {
            val o = JSONObject()
            for ((k, v) in m) o.put(k, org.json.JSONArray().put(v.time).put(v.value))
            prefs?.edit()?.putString(name, o.toString())?.apply()
        } catch (_: Exception) {}
    }
    @Synchronized fun get(key: String): String? {
        val it = loaded()[key] ?: return null
        return if (System.currentTimeMillis() - it.time > ttlMs) null else it.value
    }
    @Synchronized fun put(key: String, value: String) {
        val m = loaded()
        m.remove(key)
        m[key] = Item(value, System.currentTimeMillis())
        val it = m.entries.iterator()
        while (m.size > max && it.hasNext()) { it.next(); it.remove() }
        save(m)
    }
    @Synchronized fun remove(key: String) { val m = loaded(); if (m.remove(key) != null) save(m) }
    @Synchronized fun clear() { map = LinkedHashMap(); try { prefs?.edit()?.remove(name)?.apply() } catch (_: Exception) {} }
    val size: Int @Synchronized get() = loaded().size
}
