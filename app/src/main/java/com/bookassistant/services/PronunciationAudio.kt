package com.bookassistant.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Persistent audio cache, separate files for British (1) and American (2) pronunciation. */
class PronunciationAudio(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://dict.youdao.com/dictvoice"
) {
    private val mutex = Mutex()

    suspend fun get(text: String, british: Boolean): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = text.trim()
            require(clean.isNotEmpty() && clean.length <= 1000) { "发音文本为空或过长" }
            val type = if (british) "1" else "2"
            val digest = MessageDigest.getInstance("SHA-256").digest("$type:$clean".toByteArray())
                .joinToString("") { "%02x".format(it) }
            directory.mkdirs()
            val file = File(directory, "$digest.mp3")
            if (file.isFile && file.length() > 3) return@withLock file
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("audio", clean).addQueryParameter("type", type).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "发音服务暂不可用" }
                val body = response.body ?: error("发音服务没有返回音频")
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 3 * 1024 * 1024) { "发音音频过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size in 4..(3 * 1024 * 1024)) { "发音音频为空或过大" }
                val mp3 = bytes.take(3) == listOf(73.toByte(), 68.toByte(), 51.toByte()) ||
                    (bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224)
                check(mp3) { "发音服务返回了无效音频" }
                // Unique staging files also protect overlapping downloads across activity recreation.
                val temporary = File.createTempFile("$digest-", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    check(temporary.renameTo(file)) { "无法保存发音音频" }
                } finally { temporary.delete() }
            }
            file
        }
    }
}
