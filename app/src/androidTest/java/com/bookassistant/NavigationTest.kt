package com.bookassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun libraryAndVocabularyEmptyStatesAreReachable() {
        compose.onNodeWithText("把英语书变成读得懂的每一段").assertIsDisplayed()
        compose.onNodeWithText("生词本").performClick()
        compose.onNodeWithText("我的生词").assertIsDisplayed()
    }
}
