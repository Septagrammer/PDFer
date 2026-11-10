package com.pavlo.pdfer

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class MainActivityImportTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun viewImportSurvivesRecompositionAndNavigatesToReader() {
        val app = ApplicationProvider.getApplicationContext<PdferApp>()
        val pdf = File.createTempFile("view-import", ".pdf", app.cacheDir)
        TestFixtures.writeOnePagePdf(pdf)
        pdf.appendText("\n% view-import-${System.nanoTime()}")
        val id = MessageDigest.getInstance("SHA-256").digest(pdf.readBytes())
            .joinToString("") { "%02x".format(it) }.take(24)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                        it, Intent(Intent.ACTION_VIEW, Uri.fromFile(pdf))
                    )
                }
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            }
        } finally {
            app.library.byId(id)?.let { runBlocking { app.library.delete(it) } }
            pdf.delete()
        }
    }

    @Test
    fun invalidViewImportShowsErrorAndKeepsLibrary() {
        val app = ApplicationProvider.getApplicationContext<PdferApp>()
        val invalid = File.createTempFile("invalid-view", ".pdf", app.cacheDir)
        invalid.writeText("not a PDF")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                        it, Intent(Intent.ACTION_VIEW, Uri.fromFile(invalid))
                    )
                }
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText("Couldn’t open PDF").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Couldn’t open PDF").assertIsDisplayed()
                compose.onNodeWithText("PDFer").assertIsDisplayed()
            }
        } finally {
            invalid.delete()
        }
    }
}
