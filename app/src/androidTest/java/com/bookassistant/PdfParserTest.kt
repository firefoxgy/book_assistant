package com.bookassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookassistant.importing.BookParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfParserTest {
    @Test fun extractsTextAndRejectsImageOnlyFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File(context.cacheDir, "sample.pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(40f, 700f); stream.showText("Learning English is enjoyable.")
                    stream.endText()
                }
                document.save(file)
            }
            assertEquals("Learning English is enjoyable.", BookParser.parse(context, file, "pdf", "Sample").passages.single().second)
            PDDocument().use { document -> document.addPage(PDPage()); document.save(file) }
            assertThrows(IllegalArgumentException::class.java) { BookParser.parse(context, file, "pdf", "Empty") }
        } finally { file.delete() }
    }
}
