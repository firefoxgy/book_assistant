package com.bookassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.data.AppDatabase
import com.bookassistant.data.Book
import com.bookassistant.data.MIGRATION_1_2
import com.bookassistant.data.MIGRATION_2_3
import com.bookassistant.data.MIGRATION_3_4
import com.bookassistant.data.Passage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h800dp")
class ReadingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedPositionInLongBookIsRestoredOnOpen() {
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        val id = "long-navigation-test"
        runBlocking {
            db.books().insert(Book(id, "Long Navigation Book", "epub", "", System.currentTimeMillis(), position = 24, totalPassages = 25))
            db.books().insertPassages((0..24).map { index ->
                Passage("long-$index", id, if (index < 20) "Beginning" else "Ending", index, "Paragraph $index.", "第 ${index} 段")
            })
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Long Navigation Book").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Long Navigation Book").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Paragraph 24.").assertIsDisplayed() }.isSuccess }
        assertEquals(24, runBlocking { db.books().get(id)?.position })
        db.close()
    }

    @Test fun chapterJumpIsSavedAndRestoredOnReopen() {
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        val id = "navigation-test"
        runBlocking {
            db.books().insert(Book(id, "Navigation Test Book", "epub", "", System.currentTimeMillis(), totalPassages = 3))
            db.books().insertPassages(listOf(
                Passage("nav-0", id, "First chapter", 0, "First paragraph.", "第一段"),
                Passage("nav-1", id, "First chapter", 1, "Middle paragraph.", "中间段"),
                Passage("nav-2", id, "Second chapter", 2, "Final paragraph.", "最后一段")
            ))
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Navigation Test Book").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Navigation Test Book").performClick()
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNode(hasText("Second chapter") and hasClickAction()).performClick()
        compose.onNodeWithText("Final paragraph.").assertIsDisplayed()
        compose.waitUntil(5_000) { runBlocking { db.books().get(id)?.position == 2 } }
        assertEquals(2, runBlocking { db.books().get(id)?.position })
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithText("Navigation Test Book").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Final paragraph.").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("Final paragraph.").assertIsDisplayed()
        assertEquals(2, runBlocking { db.books().get(id)?.position })
        db.close()
    }
}
