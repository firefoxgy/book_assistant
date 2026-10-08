package com.bookassistant.services

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class OfflineEntry(
    val lemma: String, val phonetic: String, val definition: String, val chinese: String,
    val examples: List<String>,
    val attribution: String = "Open English WordNet 2025 · CC BY 4.0 · Open English WordNet Team / Princeton WordNet"
)

/** Separate read-only dictionary; upgrading it never replaces the user's vocabulary database. */
class OfflineDictionary(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var database: SQLiteDatabase? = null

    @Synchronized private fun database(): SQLiteDatabase {
        database?.let { return it }
        val file = File(context.filesDir, "dictionary-v1.db")
        if (!file.exists()) {
            val temporary = File(context.filesDir, "dictionary-v1.tmp")
            try {
                context.assets.open("dictionary-v1.db").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(temporary.renameTo(file)) { "无法安装离线词库" }
            } finally { temporary.delete() }
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
    }

    suspend fun lookup(term: String): OfflineEntry? = withContext(Dispatchers.IO) {
        val key = term.trim().lowercase(Locale.ROOT)
        val db = database()
        // Prefer the exact entry, then explicit inflections from ECDICT.
        db.rawQuery("SELECT word, phonetic, definition, chinese FROM entries WHERE word = ? " +
            "UNION ALL SELECT e.word, e.phonetic, e.definition, e.chinese FROM forms f " +
            "JOIN entries e ON e.word = f.word WHERE f.form = ? LIMIT 1", arrayOf(key, key)).use { row ->
            if (!row.moveToFirst()) return@withContext null
            val lemma = row.getString(0)
            val examples = db.rawQuery("SELECT sentence FROM examples WHERE word = ? ORDER BY length(sentence), sentence LIMIT 2", arrayOf(lemma)).use { sentences ->
                buildList { while (sentences.moveToNext()) add(sentences.getString(0)) }
            }
            OfflineEntry(lemma, row.getString(1), row.getString(2), row.getString(3), examples)
        }
    }

    @Synchronized override fun close() { database?.close(); database = null }
}
