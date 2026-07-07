package com.pavlo.pdfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.FigureFinder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FigureFinderTest {

    @Test fun detectsContinuousToneFigureNotText() {
        val w = 800; val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        // Top: thin black "text" lines (sparse), region passed as a text box.
        val textRect = Rect(80, 100, 720, 500)
        val tp = Paint().apply { color = Color.BLACK; strokeWidth = 5f }
        var y = 120
        while (y < 480) { c.drawLine(90f, y.toFloat(), 700f, y.toFloat(), tp); y += 28 }

        // Bottom: a solid mid-gray "photo" block (continuous tone), not in any text box.
        val photo = Rect(120, 700, 680, 1100)
        val gp = Paint()
        var yy = photo.top
        while (yy < photo.bottom) {
            gp.color = Color.rgb(110 + (yy % 60), 130, 140)
            c.drawRect(photo.left.toFloat(), yy.toFloat(), photo.right.toFloat(), (yy + 4).toFloat(), gp)
            yy += 4
        }

        val figs = FigureFinder.find(bmp, listOf(textRect))
        assertTrue("expected at least one figure", figs.isNotEmpty())
        assertTrue("a figure should overlap the photo", figs.any { Rect.intersects(it, photo) })
        assertTrue("no figure should sit entirely in the text band", figs.none { it.bottom <= photo.top })
        bmp.recycle()
    }

    @Test fun blankPageHasNoFigures() {
        val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        assertTrue(FigureFinder.find(bmp, emptyList()).isEmpty())
        bmp.recycle()
    }

    @Test fun sparseTextDoesNotBecomeOneBigFigure() {
        // Guards the real hazard: a page of (unrecognized) sparse text shouldn't be
        // swallowed into a single large figure block.
        val w = 800; val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; strokeWidth = 4f }
        var y = 60
        while (y < 940) { c.drawLine(60f, y.toFloat(), 740f, y.toFloat(), p); y += 26 }
        val figs = FigureFinder.find(bmp, emptyList())
        val pageArea = w.toFloat() * h
        assertTrue(
            "sparse ruled lines should not produce a large figure: $figs",
            figs.none { (it.width().toFloat() * it.height()) / pageArea > 0.4f },
        )
        bmp.recycle()
    }
}
