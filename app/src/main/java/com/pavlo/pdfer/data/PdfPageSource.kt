package com.pavlo.pdfer.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.min

/**
 * Renders pages of a single local PDF file to bitmaps.
 *
 * PdfRenderer needs a seekable file descriptor, which is why documents are
 * copied into app storage on import (see [LibraryRepository]).
 */
class PdfPageSource(file: File) : Closeable {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val pageCount: Int get() = renderer.pageCount

    /**
     * Render [index] at roughly [targetDpi], capping the longest side at
     * [maxPx] so OCR bitmaps stay within memory limits.
     */
    fun render(index: Int, targetDpi: Int = 300, maxPx: Int = 3000): Bitmap {
        renderer.openPage(index).use { page ->
            // PdfRenderer reports page size in points (1/72 inch).
            var scale = targetDpi / 72f
            val longest = maxOf(page.width, page.height) * scale
            if (longest > maxPx) scale *= maxPx / longest
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE) // OCR & scans assume white background
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }
}

/** Downscale a bitmap so its longest side is at most [maxPx] (for display). */
fun Bitmap.downscaled(maxPx: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxPx) return this
    val s = maxPx.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
        .also { if (it != this) recycle() }
}
