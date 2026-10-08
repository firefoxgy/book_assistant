package com.bookassistant

import com.bookassistant.services.Dictionary
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryTest {
    @Test fun parsesMeaningPronunciationAndExample() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"word":"learning","phonetic":"/ˈlɜːnɪŋ/","meanings":[{"partOfSpeech":"noun","definitions":[{"definition":"knowledge gained by study","example":"Learning takes time."},{"definition":"the process of gaining knowledge","example":"Reading supports learning."}]}]}]"""))
            val result = Dictionary(baseUrl = server.url("/api/v2/entries/en/").toString()).lookup("learning")
            assertEquals("/ˈlɜːnɪŋ/", result?.phonetic)
            assertEquals("noun", result?.partOfSpeech)
            assertEquals("knowledge gained by study", result?.definition)
            assertEquals(listOf("Learning takes time.", "Reading supports learning."), result?.examples)
            assertEquals("/api/v2/entries/en/learning", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun missingPhraseFallsBackToTranslation() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(Dictionary(baseUrl = server.url("/").toString()).lookup("distance learning"))
            assertEquals("/distance%20learning", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun phoneticArrayIsUsedWhenTopLevelIsMissing() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"phonetics":[{"text":"/lɜːn/"}],"meanings":[{"partOfSpeech":"verb","definitions":[{"definition":"gain knowledge"}]}]}]"""))
            assertEquals("/lɜːn/", Dictionary(baseUrl = server.url("/").toString()).lookup("learn")?.phonetic)
        } finally { server.shutdown() }
    }
}
