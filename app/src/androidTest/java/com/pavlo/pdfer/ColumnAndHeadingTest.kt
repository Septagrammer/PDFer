package com.pavlo.pdfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.ColumnSplitter
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.TessManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColumnAndHeadingTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** Draw columns of text; each column is a vertical band [x0,x1] with its own lines. */
    private fun columnBitmap(
        w: Int, h: Int, fontPx: Float, columns: List<Triple<Int, Int, List<String>>>,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; textSize = fontPx; isAntiAlias = true }
        for ((x0, _, lines) in columns) {
            var y = 120
            for (line in lines) { c.drawText(line, x0.toFloat(), y.toFloat(), p); y += (fontPx * 1.7f).toInt() }
        }
        return bmp
    }

    /** OCR text of a single column band (mirrors what the reader does per column). */
    private fun ocrBand(tess: TessManager, bmp: Bitmap, band: IntRange): String = runBlocking {
        val sub = Bitmap.createBitmap(bmp, band.first, 0, band.last - band.first + 1, bmp.height)
        val t = tess.recognize(sub, OcrQuality.FAST).lowercase()
        sub.recycle(); t
    }

    @Test fun twoColumnsAreSplitAndOrderedLeftToRight() = runBlocking {
        val tess = TessManager(ctx)
        try {
            // Short lines that fit inside each band so a clean gutter exists between them.
            val left = (1..8).map { "Left side $it" }
            val right = (1..8).map { "Right side $it" }
            val bmp = columnBitmap(
                1600, 1500, 46f,
                listOf(Triple(120, 600, left), Triple(960, 1440, right)),
            )
            val bands = ColumnSplitter.columns(bmp)
            assertTrue("expected two columns, got ${bands.size}", bands.size == 2)
            assertTrue("columns must be ordered left→right", bands[0].first < bands[1].first)
            val leftText = ocrBand(tess, bmp, bands[0])
            val rightText = ocrBand(tess, bmp, bands[1])
            bmp.recycle()
            assertTrue("first band is the left column: '$leftText'",
                leftText.contains("left") && !leftText.contains("right"))
            assertTrue("second band is the right column: '$rightText'",
                rightText.contains("right") && !rightText.contains("left"))
        } finally { tess.shutdown() }
    }

    @Test fun threeColumnsAreSplitAndOrdered() = runBlocking {
        val tess = TessManager(ctx)
        try {
            val c1 = (1..7).map { "Alpha line $it" }
            val c2 = (1..7).map { "Bravo line $it" }
            val c3 = (1..7).map { "Charlie line $it" }
            val bmp = columnBitmap(
                1650, 1400, 40f,
                listOf(Triple(100, 560, c1), Triple(640, 1100, c2), Triple(1180, 1600, c3)),
            )
            val bands = ColumnSplitter.columns(bmp)
            assertTrue("expected three columns, got ${bands.size}", bands.size == 3)
            assertTrue("columns ordered left→right",
                bands[0].first < bands[1].first && bands[1].first < bands[2].first)
            assertTrue("first band = alpha", ocrBand(tess, bmp, bands[0]).contains("alpha"))
            assertTrue("second band = bravo", ocrBand(tess, bmp, bands[1]).contains("bravo"))
            assertTrue("third band = charlie", ocrBand(tess, bmp, bands[2]).contains("charlie"))
            bmp.recycle()
        } finally { tess.shutdown() }
    }

    @Test fun fourColumnsAreSplitAndOrdered() = runBlocking {
        val tess = TessManager(ctx)
        try {
            fun col(p: String) = (1..16).map { "$p line $it" }
            val bmp = columnBitmap(
                1654, 2200, 38f,
                listOf(Triple(90, 430, col("A")), Triple(490, 830, col("B")),
                    Triple(890, 1230, col("C")), Triple(1290, 1600, col("D"))),
            )
            val bands = ColumnSplitter.columns(bmp)
            assertTrue("expected four columns, got ${bands.size}", bands.size == 4)
            assertTrue("ordered left→right", (0 until 3).all { bands[it].first < bands[it + 1].first })
            assertTrue("first band = A", ocrBand(tess, bmp, bands[0]).contains("a"))
            assertTrue("last band = D", ocrBand(tess, bmp, bands[3]).contains("d"))
            bmp.recycle()
        } finally { tess.shutdown() }
    }

    @Test fun sixColumnsAreSplitAndOrdered() = runBlocking {
        val tess = TessManager(ctx)
        try {
            fun col(p: String) = (1..16).map { "$p row $it" }
            val bmp = columnBitmap(
                2339, 1700, 36f,
                listOf(Triple(80, 420, col("P")), Triple(460, 800, col("Q")),
                    Triple(840, 1180, col("R")), Triple(1220, 1560, col("S")),
                    Triple(1600, 1940, col("T")), Triple(1980, 2300, col("U"))),
            )
            val bands = ColumnSplitter.columns(bmp)
            assertTrue("expected six columns, got ${bands.size}", bands.size == 6)
            assertTrue("ordered left→right", (0 until 5).all { bands[it].first < bands[it + 1].first })
            assertTrue("first band = P", ocrBand(tess, bmp, bands[0]).contains("p"))
            assertTrue("last band = U", ocrBand(tess, bmp, bands[5]).contains("u"))
            bmp.recycle()
        } finally { tess.shutdown() }
    }

    @Test fun singleColumnIsNotSplit() {
        val lines = (1..10).map { "This is a single column of ordinary running text line $it." }
        val bmp = columnBitmap(1400, 1500, 44f, listOf(Triple(90, 1310, lines)))
        val bands = ColumnSplitter.columns(bmp)
        bmp.recycle()
        assertTrue("a single column must not be split, got ${bands.size}", bands.size == 1)
    }

    @Test fun largerTitleIsDetectedAsHeading() = runBlocking {
        val tess = TessManager(ctx)
        try {
            val bmp = Bitmap.createBitmap(1500, 1500, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(Color.WHITE)
            // Big title
            c.drawText("Introduction Chapter Title", 90f, 160f,
                Paint().apply { color = Color.BLACK; textSize = 96f; isAntiAlias = true })
            // Smaller body lines
            val body = Paint().apply { color = Color.BLACK; textSize = 40f; isAntiAlias = true }
            var y = 340
            for (i in 1..8) {
                c.drawText("This is body paragraph line number $i with ordinary text.", 90f, y.toFloat(), body)
                y += 70
            }
            val paras = tess.recognizeParas(bmp, OcrQuality.FAST)
            bmp.recycle()
            assertTrue("a heading paragraph should be detected: ${paras.map { it.heading to it.text.take(20) }}",
                paras.any { it.heading })
            assertTrue("the big title line should be the heading",
                paras.any { it.heading && (it.text.contains("Title", true) || it.text.contains("Chapter", true)) })
            assertTrue("body paragraphs should not all be headings",
                paras.any { !it.heading })
        } finally { tess.shutdown() }
    }
}
