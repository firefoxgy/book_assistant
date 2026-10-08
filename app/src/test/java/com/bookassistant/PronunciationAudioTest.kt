package com.bookassistant

import com.bookassistant.services.PronunciationAudio
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PronunciationAudioTest {
    @get:Rule val folder = TemporaryFolder()
    private fun response() = MockResponse().setBody(Buffer().write(byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0)))

    @Test fun accentsHaveSeparatePersistentCachesAndQueriesAreEncoded() = runBlocking {
        val server = MockWebServer()
        server.start()
        val cache = folder.newFolder()
        try {
            val endpoint = server.url("/dictvoice").toString()
            val audio = PronunciationAudio(cache, endpoint = endpoint)
            server.enqueue(response())
            val us = audio.get("look up", false)
            val request = server.takeRequest().requestUrl!!
            assertEquals("look up", request.queryParameter("audio"))
            assertEquals("2", request.queryParameter("type"))
            server.enqueue(response())
            val uk = audio.get("look up", true)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("type"))
            assertNotEquals(us, uk)
            server.shutdown()
            assertEquals(us, PronunciationAudio(cache, endpoint = endpoint).get("look up", false))
            assertEquals(uk, PronunciationAudio(cache, endpoint = endpoint).get("look up", true))
        } finally { runCatching { server.shutdown() } }
    }

    @Test fun invalidResponseIsNotCachedAndRetryCanSucceed() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val cache = folder.newFolder()
            val audio = PronunciationAudio(cache, endpoint = server.url("/").toString())
            server.enqueue(MockResponse().setBody("<html>error</html>"))
            assertTrue(runCatching { audio.get("opinion", false) }.isFailure)
            assertEquals(0, cache.listFiles()!!.size)
            server.enqueue(response())
            assertTrue(audio.get("opinion", false).exists())
        } finally { server.shutdown() }
    }

    @Test fun overlappingActivityInstancesCanSafelySaveTheSamePronunciation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val cache = folder.newFolder()
            val endpoint = server.url("/").toString()
            server.enqueue(response().setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(response().setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            coroutineScope {
                val first = async { PronunciationAudio(cache, endpoint = endpoint).get("opinion", false) }
                val second = async { PronunciationAudio(cache, endpoint = endpoint).get("opinion", false) }
                assertEquals(first.await(), second.await())
            }
            assertEquals(1, cache.listFiles()!!.size)
            assertTrue(cache.listFiles()!!.single().name.endsWith(".mp3"))
        } finally { server.shutdown() }
    }
}
