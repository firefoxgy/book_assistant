package com.bookassistant

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookassistant.data.AppDatabase
import com.bookassistant.services.BookImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookImporterTest {
    private fun epub(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            put("META-INF/container.xml", "<container><rootfile full-path='book.opf'/></container>")
            put("book.opf", "<package><metadata><title>A Book</title></metadata><manifest><item id='one' href='one.xhtml'/></manifest><spine><itemref idref='one'/></spine></package>")
            put("one.xhtml", "<html><body><p>Learning is useful.</p></body></html>")
        }
    }

    @Test fun sameNamedImportsRemainDistinct() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = File(context.cacheDir, "same-name.epub")
        epub(source)
        try {
            val importer = BookImporter(context, db)
            val first = importer.import(Uri.fromFile(source), source.name)
            val second = importer.import(Uri.fromFile(source), source.name)
            assertNotEquals(first, second)
            assertEquals(2, db.books().all().first().size)
            assertEquals("Learning is useful.", db.books().passages(first).first().single().english)
        } finally {
            db.books().all().first().forEach { File(it.path).delete() }
            source.delete(); db.close()
        }
    }

    @Test fun brokenImportLeavesNoVisibleBookOrPrivateCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = File(context.cacheDir, "broken.epub")
        source.writeText("not an epub")
        val before = context.filesDir.list()?.toSet().orEmpty()
        try {
            val failure = runCatching { BookImporter(context, db).import(Uri.fromFile(source), source.name) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(db.books().all().first().isEmpty())
            assertEquals(before, context.filesDir.list()?.toSet().orEmpty())
        } finally { source.delete(); db.close() }
    }
}
