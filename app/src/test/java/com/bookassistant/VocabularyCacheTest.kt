package com.bookassistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.data.AppDatabase
import com.bookassistant.data.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyCacheTest {
    @Test fun savedEntryAndExampleAttributionSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "vocabulary-cache-test.db"
        context.deleteDatabase(name)
        val original = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        original.words().put(Word("learn", "learn", "学习", "/lɜːn/", "verb · gain knowledge", "I want to learn English.", "Book", 1, "我想学习英语。", "Tatoeba · CC BY 2.0 FR · writer1"))
        original.close()
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val saved = reopened.words().get("learn")!!
            assertEquals("学习", saved.chinese)
            assertEquals("I want to learn English.", saved.example)
            assertEquals("我想学习英语。", saved.exampleChinese)
            assertEquals("Tatoeba · CC BY 2.0 FR · writer1", saved.exampleAttribution)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
