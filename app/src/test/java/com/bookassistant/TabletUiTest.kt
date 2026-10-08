package com.bookassistant

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class TabletUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun wideScreenUsesNavigationRail() {
        compose.onNodeWithTag("tablet-rail").assertExists()
        compose.onNodeWithTag("phone-bottom-nav").assertDoesNotExist()
    }
}
