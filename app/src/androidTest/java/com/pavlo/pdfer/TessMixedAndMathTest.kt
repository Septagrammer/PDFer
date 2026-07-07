package com.pavlo.pdfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.TessManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TessMixedAndMathTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun multiLineBitmap(lines: List<String>): Bitmap {
        val w = 1300
        val lineH = 130
        val bmp = Bitmap.createBitmap(w, 160 + lines.size * lineH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; textSize = 72f; isAntiAlias = true }
        var y = 150
        for (l in lines) { c.drawText(l, 60f, y.toFloat(), p); y += lineH }
        return bmp
    }

    @Test fun recognizesUkrainianMixedWithEnglish() = runBlocking {
        val tess = TessManager(ctx)
        try {
            val bmp = multiLineBitmap(listOf("Привіт world", "це тест recognition"))
            val text = tess.recognize(bmp, OcrQuality.FAST).lowercase()
            bmp.recycle()
            assertTrue("English words not recognized: '$text'",
                text.contains("world") || text.contains("recognition"))
            assertTrue("Ukrainian words not recognized: '$text'",
                text.contains("тест") || text.contains("привіт") || text.contains("це"))
        } finally {
            tess.shutdown()
        }
    }

    @Test fun mainEngineReadsSimpleFormulaAsText() = runBlocking {
        // Formulas are kept as text via the main OCR engine (the legacy equ model was
        // dropped because it mangled clean input). A simple formula must survive.
        val tess = TessManager(ctx)
        try {
            val bmp = multiLineBitmap(listOf("1 + 2 = 3"))
            val r = tess.recognize(bmp, OcrQuality.FAST)
            bmp.recycle()
            assertTrue("digits not recognized: '$r'", r.count { it.isDigit() } >= 2)
            assertTrue("'=' not recognized: '$r'", r.contains("="))
        } finally {
            tess.shutdown()
        }
    }
}
