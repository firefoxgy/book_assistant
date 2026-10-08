package com.bookassistant

import com.bookassistant.data.Passage

internal data class ChapterEntry(val title: String, val position: Int)

internal fun chapterEntries(passages: List<Passage>): List<ChapterEntry> = buildList {
    var previous: String? = null
    for (passage in passages) {
        val title = passage.chapter.ifBlank { "开始阅读" }
        if (title != previous) add(ChapterEntry(title, passage.position))
        previous = title
    }
}

internal class ReadingPositionTracker {
    private var lastPosition: Int? = null

    fun restored(position: Int) { lastPosition = position }

    fun shouldSave(position: Int): Boolean {
        if (lastPosition == null || lastPosition == position) return false
        lastPosition = position
        return true
    }
}
