package com.pavlo.pdfer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.ui.AppTheme
import com.pavlo.pdfer.ui.LibraryScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Smoke test: the library screen renders its app-bar title and primary action.
     * The "Open PDF" FAB is present regardless of whether the library is empty, so
     * this is robust to shared on-disk state from other tests. The FAB text lives
     * under a ClearAndSetSemantics node, so it must be matched on the unmerged tree.
     */
    @Test
    fun rendersTitleAndOpenPdfAction() {
        val app = ApplicationProvider.getApplicationContext<PdferApp>()
        composeRule.setContent {
            AppTheme {
                LibraryScreen(app = app, onOpen = {})
            }
        }
        composeRule.onNodeWithText("PDFer").assertIsDisplayed()
        composeRule.onNodeWithText("Open PDF", useUnmergedTree = true).assertExists()
    }
}
