package com.bookassistant

import com.bookassistant.importing.BookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private fun epub(broken: Boolean = false, nestedList: Boolean = false, relativeChapter: Boolean = false): File {
        val file = File.createTempFile("reader-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            val opfPath = if (relativeChapter) "OPS/package/book.opf" else "OPS/book.opf"
            val chapterPrefix = if (relativeChapter) "OPS/Text/" else "OPS/"
            val hrefPrefix = if (relativeChapter) "../Text/" else ""
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='$opfPath'/></rootfiles></container>")
            entry(opfPath, "<package><metadata><title>Test Book</title></metadata><manifest><item id='a' href='${hrefPrefix}a.xhtml'/><item id='b' href='${hrefPrefix}b.xhtml'/></manifest><spine><itemref idref='b'/><itemref idref='a'/></spine></package>")
            if (!broken) entry("${chapterPrefix}b.xhtml", "<html><body><h1>Second</h1><p>Two comes first.</p></body></html>")
            entry("${chapterPrefix}a.xhtml", if (nestedList) "<html><body><h1>First</h1><ul><li><p>One comes second.</p></li></ul></body></html>" else "<html><body><h1>First</h1><p>One comes second.</p></body></html>")
        }
        return file
    }

    @Test fun followsSpineInsteadOfZipOrder() {
        val file = epub()
        try {
            val book = BookParser.parseEpub(file, "fallback")
            assertEquals("Test Book", book.title)
            assertEquals(listOf("Two comes first.", "One comes second."), book.passages.map { it.second })
        } finally { file.delete() }
    }

    @Test fun missingSpineChapterFails() {
        val file = epub(broken = true)
        try { assertThrows(IllegalStateException::class.java) { BookParser.parseEpub(file, "fallback") } }
        finally { file.delete() }
    }

    @Test fun nestedParagraphIsNotRepeated() {
        val file = epub(nestedList = true)
        try { assertEquals(listOf("Two comes first.", "One comes second."), BookParser.parseEpub(file, "fallback").passages.map { it.second }) }
        finally { file.delete() }
    }

    @Test fun relativeChapterPathResolvesAgainstOpf() {
        val file = epub(relativeChapter = true)
        try { assertEquals(2, BookParser.parseEpub(file, "fallback").passages.size) }
        finally { file.delete() }
    }

    @Test fun navigationDocumentOverridesRepeatedHtmlTitle() {
        val file = File.createTempFile("reader-nav-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfile full-path='OPS/book.opf'/></container>")
            entry("OPS/book.opf", "<package><metadata><title>Collection</title></metadata><manifest><item id='toc' href='toc.ncx'/><item id='a' href='a.xhtml'/><item id='b' href='b.xhtml'/></manifest><spine toc='toc'><itemref idref='a'/><itemref idref='b'/></spine></package>")
            entry("OPS/toc.ncx", "<ncx><navMap><navPoint><navLabel><text>Chapter One</text></navLabel><content src='a.xhtml'/></navPoint><navPoint><navLabel><text>Chapter Two</text></navLabel><content src='b.xhtml'/></navPoint></navMap></ncx>")
            entry("OPS/a.xhtml", "<html><head><title>Collection</title></head><body><p>First text.</p></body></html>")
            entry("OPS/b.xhtml", "<html><head><title>Collection</title></head><body><p>Second text.</p></body></html>")
        }
        try {
            assertEquals(listOf("Chapter One", "Chapter Two"), BookParser.parseEpub(file, "fallback").passages.map { it.first })
        } finally { file.delete() }
    }

    @Test fun suppliedCollectionExposesItsChaptersWhenFixtureIsAvailable() {
        val path = System.getenv("BOOK_ASSISTANT_EPUB_FIXTURE")
        assumeTrue(path != null && File(path).isFile)
        val chapters = BookParser.parseEpub(File(path!!), "fallback").passages.map { it.first }.distinct()
        assertTrue(chapters.size > 100)
        assertTrue("Chapter 1 The Boy Who Lived" in chapters)
    }
}
