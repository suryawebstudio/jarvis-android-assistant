package com.surya.jarvis

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Small, private, on-device memory store. It deliberately stores facts only,
 * not the full conversation transcript or credentials.
 */
class JarvisMemoryStore(context: Context) {
    companion object {
        private const val PREFS = "jarvis_memory"
        private const val KEY_MEMORIES = "memories"
        private const val MAX_MEMORIES = 50
        private const val MAX_LENGTH = 300
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<String> {
        val raw = preferences.getString(KEY_MEMORIES, "[]") ?: "[]"
        return try {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val value = json.optString(index).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun remember(fact: String): Boolean {
        val clean = normalize(fact) ?: return false
        val current = all().toMutableList()
        if (current.any { it.equals(clean, ignoreCase = true) }) return false
        current.add(0, clean)
        save(current.take(MAX_MEMORIES))
        return true
    }

    @Synchronized
    fun forget(query: String): Int {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isEmpty()) return 0
        val current = all()
        val remaining = current.filterNot {
            it.lowercase(Locale.ROOT).contains(cleanQuery)
        }
        save(remaining)
        return current.size - remaining.size
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_MEMORIES).apply()
    }

    fun summary(): String {
        val memories = all()
        if (memories.isEmpty()) return "No saved memories."
        return memories.mapIndexed { index, memory -> "${index + 1}. $memory" }
            .joinToString("\n")
    }

    private fun normalize(value: String): String? {
        val clean = value.replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '!', '?')
        return clean.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    private fun save(values: List<String>) {
        val json = JSONArray()
        values.forEach(json::put)
        preferences.edit().putString(KEY_MEMORIES, json.toString()).apply()
    }
}
