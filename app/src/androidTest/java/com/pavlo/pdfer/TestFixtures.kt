package com.pavlo.pdfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * Helpers shared by the instrumented tests: build real PDFs and bitmaps at
 * runtime so the tests exercise the actual Android graphics/PDF stack.
 */
object TestFixtures {

    /**
     * Write a real single-page PDF to [out] using the framework PdfDocument.
     * Draws a filled rectangle and some text so the page is not blank.
     */
    fun writeOnePagePdf(
        out: File,
        widthPt: Int = 612,   // US Letter @ 72dpi
        heightPt: Int = 792,
        text: String = "Reflow Reader test page",
    ): File {
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val rect = Paint().apply { color = Color.LTGRAY }
            canvas.drawRect(Rect(40, 40, widthPt - 40, 120), rect)

            val textPaint = Paint().apply {
                color = Color.BLACK
                textSize = 28f
                isAntiAlias = true
            }
            canvas.drawText(text, 60f, 90f, textPaint)
            doc.finishPage(page)

            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return out
    }

    /**
     * A large white bitmap with [text] drawn in big black type. Used to feed OCR
     * and the image-enhancement pipeline.
     */
    fun textBitmap(
        text: String = "Hello World OCR",
        width: Int = 1200,
        height: Int = 400,
        textSize: Float = 110f,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            this.textSize = textSize
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        // Vertically roughly centered.
        val baseline = height / 2f + textSize / 3f
        canvas.drawText(text, 40f, baseline, paint)
        return bmp
    }

    /**
     * White bitmap with several black horizontal "text" lines. Good input for the
     * deskew/binarize pipeline without depending on real glyph rendering.
     */
    fun ruledLinesBitmap(width: Int = 1000, height: Int = 700): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val line = Paint().apply { color = Color.BLACK }
        var y = 60
        while (y < height - 40) {
            canvas.drawRect(Rect(80, y, width - 80, y + 18), line)
            y += 70
        }
        return bmp
    }

    /** Rotate [src] by [deg] degrees onto a fresh white bitmap of the same size. */
    fun rotated(src: Bitmap, deg: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix().apply { postRotate(deg, src.width / 2f, src.height / 2f) }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Fraction (0..1) of opaque pixels that are "near black or near white".
     * Used to assert a bitmap is effectively binarized.
     */
    fun nearBinaryFraction(bmp: Bitmap, tol: Int = 24): Double {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var counted = 0
        var binary = 0
        for (p in px) {
            if (Color.alpha(p) == 0) continue
            counted++
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val nearBlack = r <= tol && g <= tol && b <= tol
            val nearWhite = r >= 255 - tol && g >= 255 - tol && b >= 255 - tol
            if (nearBlack || nearWhite) binary++
        }
        return if (counted == 0) 1.0 else binary.toDouble() / counted
    }
}
