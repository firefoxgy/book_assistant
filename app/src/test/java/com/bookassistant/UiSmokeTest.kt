package com.bookassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h800dp")
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun phoneCanOpenLibraryAndNotebook() {
        compose.onNodeWithText("把英语书变成读得懂的每一段").assertIsDisplayed()
        compose.onNodeWithText("生词本").performClick()
        compose.onNodeWithText("我的生词").assertIsDisplayed()
    }
}
