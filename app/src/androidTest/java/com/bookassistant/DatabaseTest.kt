package com.bookassistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookassistant.data.AppDatabase
import com.bookassistant.data.Book
import com.bookassistant.data.Passage
import com.bookassistant.data.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test fun deletingBookKeepsSavedWord() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.books().insert(Book("b", "Book", "epub", "", 1))
            db.books().insertPassages(listOf(Passage("p", "b", "", 0, "Hello")))
            db.words().put(Word("hello", "Hello", "你好", "", "greeting", "Hello, world.", "Book", 1, "你好，世界。"))
            db.books().delete("b")
            assertNull(db.books().get("b"))
            assertEquals("你好", db.words().get("hello")?.chinese)
            assertEquals("你好，世界。", db.words().get("hello")?.exampleChinese)
        } finally { db.close() }
    }
}
