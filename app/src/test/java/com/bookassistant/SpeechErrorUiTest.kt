package com.bookassistant

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.semantics.SemanticsActions
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
class SpeechErrorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun errorIsVisibleAboveTheWordSheetInsteadOfBelowScrollableContent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            runBlocking { db.words().put(Word("opinion", "opinion", "意见", "", "a belief", "", "Book", 1)) }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("生词本").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("opinion").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("opinion") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("美式发音").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
            // Inject the sheet's accessibility click: Robolectric modal windows don't receive pointer injection.
            compose.onNodeWithText("美式发音").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("发音未能播放").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("发音未能播放").assertIsDisplayed()
            compose.onNodeWithText("媒体音量为 0", substring = true).assertIsDisplayed()
            compose.onNodeWithText("语音设置").assertIsDisplayed()
            compose.onNodeWithText("知道了").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("发音未能播放").fetchSemanticsNodes().isEmpty() }
        } finally { db.close() }
    }
}
