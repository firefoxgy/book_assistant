package com.bookassistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.services.OfflineDictionary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineDictionaryTest {
    @Test fun bundledDictionaryWorksWithoutNetworkAndSurvivesReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OfflineDictionary(context).use { dictionary ->
            val learn = dictionary.lookup(" LEARN ")!!
            assertTrue(learn.chinese.contains("学"))
            assertTrue(learn.phonetic.isNotBlank())
            assertTrue(learn.definition.isNotBlank())
            assertTrue(learn.examples.isNotEmpty())
            assertTrue(learn.attribution.contains("WordNet"))
            assertTrue(dictionary.lookup("went")!!.chinese.contains("go"))
            assertEquals("action", dictionary.lookup("actioning")!!.lemma)
            assertNotNull(dictionary.lookup("look up"))
            assertNull(dictionary.lookup("zzzzunknowntermzzzz"))
            assertNull(dictionary.lookup("' OR 1=1 --"))
        }
        OfflineDictionary(context).use { assertTrue(it.lookup("book")!!.chinese.contains("书")) }
    }
}
