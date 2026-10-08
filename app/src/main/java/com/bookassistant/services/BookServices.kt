package com.bookassistant.services

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.bookassistant.data.*
import com.bookassistant.importing.BookParser
import com.google.mlkit.nl.translate.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class BookImporter(private val context: Context, private val db: AppDatabase) {
    suspend fun refreshChapters(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val book = db.books().get(bookId) ?: return@withContext false
        if (book.format != "epub") return@withContext false
        val stored = db.books().passages(bookId).first()
        val parsed = BookParser.parseEpub(File(book.path), book.title).passages
        if (stored.size != parsed.size || stored.indices.any { stored[it].english != parsed[it].second }) return@withContext false
        db.withTransaction {
            stored.indices.forEach { index ->
                val chapter = parsed[index].first
                if (stored[index].chapter != chapter) db.books().chapter(stored[index].id, chapter)
            }
        }
        true
    }

    suspend fun import(uri: Uri, name: String): String = withContext(Dispatchers.IO) {
        val format = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(format == "pdf" || format == "epub") { "请选择 PDF 或 EPUB 文件" }
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "$id.$format")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                ?: error("无法读取文件")
            val signature = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            require(
                if (format == "pdf") signature.contentEquals(byteArrayOf(37, 80, 68, 70))
                else signature[0] == 80.toByte() && signature[1] == 75.toByte()
            ) { "文件内容与扩展名不符" }
            val parsed = BookParser.parse(context, file, format, name.substringBeforeLast('.'))
            db.withTransaction {
                db.books().insert(Book(id, parsed.title, format, file.absolutePath, System.currentTimeMillis(), totalPassages = parsed.passages.size))
                db.books().insertPassages(parsed.passages.mapIndexed { index, (chapter, text) ->
                    Passage("$id:$index", id, chapter, index, text)
                })
            }
            id
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}

class Translator : AutoCloseable {
    private var client: com.google.mlkit.nl.translate.Translator? = null
    private var modelReady = false
    private val mutex = Mutex()

    private fun client(): com.google.mlkit.nl.translate.Translator = client ?: Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE).build()
    ).also { client = it }

    /** Never downloads a model or accesses the network during an offline dictionary hit. */
    suspend fun translateDownloaded(text: String): String? = mutex.withLock {
        if (!modelReady) {
            val model = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
            val downloaded = suspendCancellableCoroutine<Boolean> { continuation ->
                RemoteModelManager.getInstance().isModelDownloaded(model)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
            }
            if (!downloaded) return@withLock null
        }
        suspendCancellableCoroutine { continuation ->
            client().translate(text)
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        }
    }

    suspend fun translate(text: String): String = mutex.withLock {
        val translator = client()
        if (!modelReady) {
            awaitUnit { success, failure ->
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { success() }.addOnFailureListener(failure)
            }
            modelReady = true
        }
        suspendCancellableCoroutine { continuation ->
            translator.translate(text).addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) }
        }
    }

    private suspend fun awaitUnit(start: (() -> Unit, (Exception) -> Unit) -> Unit) =
        suspendCancellableCoroutine<Unit> { continuation ->
            start({ if (continuation.isActive) continuation.resume(Unit) }, { if (continuation.isActive) continuation.resumeWith(Result.failure(it)) })
        }

    override fun close() { client?.close() }
}

data class DictionaryResult(val phonetic: String, val partOfSpeech: String, val definition: String, val examples: List<String>)

data class ExternalExamples(val sentences: List<String>, val attribution: String)

class Examples(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(6, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://api.tatoeba.org/v1/sentences"
) {
    suspend fun lookup(term: String): ExternalExamples = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", term).addQueryParameter("lang", "eng")
            .addQueryParameter("sort", "relevance").addQueryParameter("limit", "20").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext ExternalExamples(emptyList(), "")
            val data = org.json.JSONObject(response.body?.string().orEmpty()).optJSONArray("data")
                ?: return@withContext ExternalExamples(emptyList(), "")
            val candidates = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .filter { it.optString("text").length in 18..180 }
                .filter { it.optString("text").contains(term, ignoreCase = true) }
                .distinctBy { it.optString("text") }.take(2)
            val sentences = candidates.map { it.optString("text") }
            val licenses = candidates.mapNotNull { it.optString("license").takeIf(String::isNotBlank) }.distinct()
            val owners = candidates.mapNotNull { it.optString("owner").takeIf(String::isNotBlank) }.distinct()
            val attribution = if (sentences.isEmpty()) "" else listOf("Tatoeba", licenses.joinToString(", "), owners.joinToString(", ")).filter(String::isNotBlank).joinToString(" · ")
            ExternalExamples(sentences, attribution)
        }
    }
}

class Dictionary(private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(), private val baseUrl: String = "https://api.dictionaryapi.dev/api/v2/entries/en/") {
    suspend fun lookup(word: String): DictionaryResult? = withContext(Dispatchers.IO) {
        val safe = java.net.URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        val request = Request.Builder().url("$baseUrl$safe").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val root = JSONArray(response.body?.string() ?: return@withContext null).getJSONObject(0)
            val meanings = root.optJSONArray("meanings") ?: return@withContext null
            val first = meanings.optJSONObject(0) ?: return@withContext null
            val definition = first.optJSONArray("definitions")?.optJSONObject(0) ?: return@withContext null
            val examples = buildList {
                for (meaningIndex in 0 until meanings.length()) {
                    val definitions = meanings.optJSONObject(meaningIndex)?.optJSONArray("definitions") ?: continue
                    for (definitionIndex in 0 until definitions.length()) {
                        definitions.optJSONObject(definitionIndex)?.optString("example")?.takeIf(String::isNotBlank)?.let { add(it) }
                    }
                }
            }.distinct().take(3)
            val fallbackPhonetic = root.optJSONArray("phonetics")?.let { entries ->
                (0 until entries.length()).asSequence().mapNotNull { entries.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.firstOrNull()
            }.orEmpty()
            DictionaryResult(root.optString("phonetic").ifBlank { fallbackPhonetic }, first.optString("partOfSpeech"), definition.optString("definition"), examples)
        }
    }
}
