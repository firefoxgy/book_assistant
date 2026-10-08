package com.bookassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bookassistant.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h800dp")
class TranslationVisibilityTest : TranslationVisibilityScenario()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class TabletTranslationVisibilityTest : TranslationVisibilityScenario()

abstract class TranslationVisibilityScenario {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun canHideAndRestoreTranslationsAfterRecreation() {
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            runBlocking {
                db.books().insert(Book("translation-test", "Translation Test Book", "pdf", "", 1, totalPassages = 2))
                db.books().insertPassages(listOf(
                    Passage("translation-0", "translation-test", "Page 1", 0, "First English paragraph.", "第一段中文。"),
                    Passage("translation-1", "translation-test", "Page 1", 1, "Second English paragraph.", "第二段中文。")
                ))
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Translation Test Book").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Translation Test Book").performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("第一段中文。").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("第一段中文。").assertIsDisplayed()
            compose.onNodeWithText("第二段中文。").assertIsDisplayed()
            compose.onNodeWithText("关闭翻译").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("第一段中文。").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("第二段中文。").assertDoesNotExist()
            compose.onNodeWithText("First English paragraph.").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("打开翻译").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("第一段中文。").assertDoesNotExist()
            compose.onNodeWithText("打开翻译").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("第一段中文。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("第一段中文。").assertIsDisplayed()
            compose.onNodeWithText("第二段中文。").assertIsDisplayed()
        } finally { db.close() }
    }
}
