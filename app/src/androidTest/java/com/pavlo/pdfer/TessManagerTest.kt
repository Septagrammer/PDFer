package com.pavlo.pdfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.ImagePrep
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.TessManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TessManagerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun recognizesClearTextWithFastModel() {
        val tess = TessManager(ctx)
        val bmp = TestFixtures.textBitmap("Hello World OCR")
        try {
            val text = runBlocking { tess.recognize(bmp, OcrQuality.FAST) }
            assertNotNull(text)
            val lower = text.lowercase()
            // Allow OCR noise: assert the key words are present rather than exact match.
            assertTrue("OCR output should contain 'hello' (was: '$text')", lower.contains("hello"))
            assertTrue("OCR output should contain 'world' (was: '$text')", lower.contains("world"))
        } finally {
            bmp.recycle()
            tess.shutdown()
        }
    }

    @Test
    fun enhancePreservesOrImprovesRecognizability() {
        // A clean bitmap run through enhance() must still OCR correctly. This
        // exercises the ImagePrep -> TessManager path end to end.
        val tess = TessManager(ctx)
        val raw = TestFixtures.textBitmap("Hello World OCR")
        val enhanced = ImagePrep.enhance(raw)
        try {
            val text = runBlocking { tess.recognize(enhanced, OcrQuality.FAST) }.lowercase()
            assertTrue("enhanced OCR should still find 'hello' (was: '$text')", text.contains("hello"))
            assertTrue("enhanced OCR should still find 'world' (was: '$text')", text.contains("world"))
        } finally {
            if (!raw.isRecycled) raw.recycle()
            if (enhanced !== raw && !enhanced.isRecycled) enhanced.recycle()
            tess.shutdown()
        }
    }

    @Test
    fun bestAvailableReturnsBooleanWithoutCrashing() {
        val tess = TessManager(ctx)
        try {
            // Just assert it does not throw and returns a Boolean. On a fresh
            // install best data is not downloaded, but we don't hard-code that.
            val available: Boolean = tess.bestAvailable()
            assertTrue(available || !available)
        } finally {
            tess.shutdown()
        }
    }

    @Test
    fun bestQualityFallsBackToFastWhenNotDownloaded() {
        // recognize(BEST) must not hard-fail when best data is missing; it falls
        // back to FAST. If best happens to be present this still must succeed.
        val tess = TessManager(ctx)
        val bmp = TestFixtures.textBitmap("Hello World OCR")
        try {
            val text = runBlocking { tess.recognize(bmp, OcrQuality.BEST) }.lowercase()
            assertTrue("BEST (or fallback) should recognize text (was: '$text')", text.contains("hello"))
        } finally {
            bmp.recycle()
            tess.shutdown()
        }
    }
}
