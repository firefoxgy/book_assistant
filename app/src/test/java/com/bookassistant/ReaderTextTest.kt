package com.bookassistant

import com.bookassistant.importing.BookParser
import com.bookassistant.data.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextTest {
    @Test fun tappedWordKeepsContractions() {
        assertEquals("don't", wordAt("Please don't stop.", 9))
        assertEquals("", wordAt("Hi", 3))
    }

    @Test fun longParagraphSplitsWithoutDroppingWords() {
        val source = "A sentence. ".repeat(90).trim()
        val pieces = BookParser.splitLong(source)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 500 })
        assertEquals(source, pieces.joinToString(" "))
    }

    @Test fun tappedWordOffersNearbyPhrases() {
        assertEquals(listOf("distance learning", "try distance learning"), phrasesAt("We try distance learning today.", 8))
    }

    @Test fun exampleFallsBackToContainingSentence() {
        assertEquals("Learning takes time.", sentenceAt("Read daily. Learning takes time. Keep going.", 15))
    }

    @Test fun libraryProgressUsesPassagePosition() {
        assertEquals(50, readingProgress(Book("b", "Book", "epub", "", 0, position = 4, totalPassages = 10)))
        assertEquals(0, readingProgress(Book("b", "Book", "epub", "", 0)))
    }

    @Test fun phraseLookupNormalizesWhitespaceAndPunctuation() {
        assertEquals("distance learning", normalizeQuery("  distance  \n learning!  "))
    }
}
