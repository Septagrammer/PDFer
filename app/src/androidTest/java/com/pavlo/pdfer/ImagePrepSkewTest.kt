package com.pavlo.pdfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.ImagePrep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePrepSkewTest {

    private fun linedPage(): Bitmap {
        val bmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 5f }
        var y = 80
        while (y < 1120) { c.drawLine(80f, y.toFloat(), 820f, y.toFloat(), p); y += 34 }
        return bmp
    }

    @Test fun estimatesCorrectingAngleForTiltedPage() {
        val straight = linedPage()
        val tilted = ImagePrep.rotate(straight, 3f)   // tilt the page +3°
        val est = ImagePrep.estimateSkewDegrees(tilted)
        // The correcting rotation is the opposite sign and roughly the same magnitude.
        assertTrue("estimate should be a meaningful negative correction, was $est", est < -0.5f)
        assertTrue("estimate within search range, was $est", est >= -4f)
        straight.recycle(); tilted.recycle()
    }

    @Test fun straightPageNeedsLittleCorrection() {
        val straight = linedPage()
        val est = ImagePrep.estimateSkewDegrees(straight)
        assertTrue("near-zero skew expected, was $est", kotlin.math.abs(est) <= 1.0f)
        straight.recycle()
    }

    @Test fun binarizeProducesNearBilevelOutput() {
        val src = linedPage()
        val out = ImagePrep.binarize(src)
        val w = out.width; val h = out.height
        val px = IntArray(w * h); out.getPixels(px, 0, w, 0, 0, w, h)
        var bilevel = 0
        for (p in px) {
            val l = (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) shr 8
            if (l < 24 || l > 231) bilevel++
        }
        assertEquals(1.0, bilevel.toDouble() / px.size, 0.02)   // essentially all black/white
        src.recycle(); out.recycle()
    }
}
