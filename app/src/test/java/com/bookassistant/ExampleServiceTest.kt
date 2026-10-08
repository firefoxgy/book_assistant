package com.bookassistant

import com.bookassistant.services.Examples
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleServiceTest {
    @Test fun returnsExternalSentenceWithAttribution() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"text":"I want to learn English.","owner":"writer1","license":"CC BY 2.0 FR"},{"text":"We learn something new every day.","owner":"writer2","license":"CC BY 2.0 FR"}]}"""))
            val result = Examples(baseUrl = server.url("/v1/sentences").toString()).lookup("learn")
            assertEquals(listOf("I want to learn English.", "We learn something new every day."), result.sentences)
            assertEquals("Tatoeba · CC BY 2.0 FR · writer1, writer2", result.attribution)
            assertEquals("learn", server.takeRequest().requestUrl?.queryParameter("q"))
        } finally { server.shutdown() }
    }
}
