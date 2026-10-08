package com.bookassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.data.*
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h800dp")
class OfflineLookupFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun phonePhraseLookupCanBeSavedAndReadFromNotebook() = checkPhraseLookup(modal = true)

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun tabletPhraseLookupCanBeSavedAndReadFromNotebook() = checkPhraseLookup(modal = false)

    private fun checkPhraseLookup(modal: Boolean) {
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            runBlocking {
                db.books().insert(Book("offline-test", "Offline Test Book", "pdf", "", 1, totalPassages = 1))
                db.books().insertPassages(listOf(Passage("offline-p", "offline-test", "Page 1", 0, "Look up the word.", "查询这个词。")))
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Offline Test Book").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Offline Test Book").performClick()
            compose.onNodeWithText("查询单词或短语").performScrollTo().performTextInput("look up")
            compose.onNodeWithContentDescription("查询短语").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("向上看", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val save = compose.onNodeWithText("加入生词本").performScrollTo().assertIsDisplayed().assertIsEnabled()
            // Robolectric does not dispatch injected touches to the separate modal sheet window.
            // Exercise its accessibility action; the tablet side panel uses pointer clicks.
            if (modal) save.performSemanticsAction(SemanticsActions.OnClick) { it() } else save.performClick()
            compose.waitUntil(5_000) { runBlocking { db.words().get("look up") != null } }
            val saved = runBlocking { db.words().get("look up") }!!
            assertTrue(saved.chinese.contains("查寻"))
            assertEquals("", saved.example)
            val close = compose.onNodeWithContentDescription("关闭词条").performScrollTo()
            if (modal) close.performSemanticsAction(SemanticsActions.OnClick) { it() } else close.performClick()
            compose.onNodeWithText("生词本").performClick()
            compose.onNodeWithText("我的生词").assertIsDisplayed()
            compose.onNodeWithText("look up").assertIsDisplayed()
        } finally { db.close() }
    }
}
