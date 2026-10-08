package com.bookassistant

import com.bookassistant.data.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingNavigationTest {
    private fun passage(position: Int, chapter: String) = Passage("p$position", "book", chapter, position, "Text $position")

    @Test fun tableOfContentsUsesFirstPassageOfEachChapter() {
        val passages = listOf(passage(0, "Intro"), passage(1, "Intro"), passage(2, "Part One"), passage(3, "Part One"), passage(4, "Part Two"))
        assertEquals(listOf(ChapterEntry("Intro", 0), ChapterEntry("Part One", 2), ChapterEntry("Part Two", 4)), chapterEntries(passages))
    }

    @Test fun unnamedPassagesHaveAStartEntry() {
        assertEquals(listOf(ChapterEntry("开始阅读", 0)), chapterEntries(listOf(passage(0, ""), passage(1, ""))))
    }

    @Test fun openingPositionDoesNotOverwriteSavedPositionBeforeRestore() {
        val tracker = ReadingPositionTracker()
        assertFalse(tracker.shouldSave(0))
        tracker.restored(5)
        assertFalse(tracker.shouldSave(5))
        assertTrue(tracker.shouldSave(6))
        assertFalse(tracker.shouldSave(6))
    }
}
