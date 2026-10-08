package com.bookassistant

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.data.AppDatabase
import com.bookassistant.data.Book
import com.bookassistant.data.Passage
import com.bookassistant.services.BookImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterRefreshTest {
    @Test fun updatesChaptersOfExistingImportWithoutLosingTranslationOrPosition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("existing-book-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfile full-path='book.opf'/></container>")
            entry("book.opf", "<package><metadata><title>Collection</title></metadata><manifest><item id='toc' href='toc.ncx'/><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/></manifest><spine toc='toc'><itemref idref='a'/><itemref idref='b'/></spine></package>")
            entry("toc.ncx", "<ncx><navMap><navPoint><navLabel><text>One</text></navLabel><content src='a.xhtml'/></navPoint><navPoint><navLabel><text>Two</text></navLabel><content src='b.xhtml'/></navPoint></navMap></ncx>")
            entry("a.xhtml", "<html><head><title>Collection</title></head><body><p>First text.</p></body></html>")
            entry("b.xhtml", "<html><head><title>Collection</title></head><body><p>Second text.</p></body></html>")
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.books().insert(Book("existing", "Collection", "epub", file.absolutePath, 1, position = 1, totalPassages = 2))
            db.books().insertPassages(listOf(
                Passage("existing:0", "existing", "Collection", 0, "First text.", "第一段"),
                Passage("existing:1", "existing", "Collection", 1, "Second text.", "第二段")
            ))
            assertTrue(BookImporter(context, db).refreshChapters("existing"))
            val passages = db.books().passages("existing").first()
            assertEquals(listOf("One", "Two"), passages.map { it.chapter })
            assertEquals(listOf("第一段", "第二段"), passages.map { it.chinese })
            assertEquals(1, db.books().get("existing")?.position)
        } finally { db.close(); file.delete() }
    }
}
