package com.pavlo.pdfer.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs

/**
 * Lightweight scan enhancement, pure Kotlin (no OpenCV):
 *   grayscale → deskew (projection-profile angle search) → Otsu binarization.
 *
 * Helps Tesseract on tilted, low-contrast, or noisy scans. The original page is
 * never modified — this runs only on the bitmap fed to OCR.
 */
object ImagePrep {

    fun enhance(src: Bitmap, binarize: Boolean = true): Bitmap {
        val gray = toGray(src)
        val angle = estimateSkewDegrees(gray)
        val deskewed = if (abs(angle) >= 0.4f) rotate(gray, angle).also { gray.recycle() } else gray
        if (!binarize) return deskewed
        return otsu(deskewed).also { if (it != deskewed) deskewed.recycle() }
    }

    /** Smallest skew worth correcting, in degrees. */
    const val MIN_SKEW = 0.4f

    /** Rotate [src] by [deg] around its center, same size, white background. Original colors kept. */
    fun rotate(src: Bitmap, deg: Float): Bitmap = rotateWhite(src, deg)

    /** Grayscale + Otsu binarization (no deskew). For feeding aligned pages to OCR. */
    fun binarize(src: Bitmap): Bitmap {
        val gray = toGray(src)
        return otsu(gray).also { if (it != gray) gray.recycle() }
    }

    internal fun luma(p: Int): Int =
        (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) shr 8

    private fun toGray(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val g = luma(px[i])
            px[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rotateWhite(src: Bitmap, deg: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val m = Matrix().apply { postRotate(deg, src.width / 2f, src.height / 2f) }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Estimate skew in degrees by rotating a downscaled copy through a small range
     * and picking the angle whose horizontal dark-pixel projection is most "peaky"
     * (text rows line up → high sum of squared per-row darkness).
     */
    fun estimateSkewDegrees(gray: Bitmap): Float {
        val scale = if (gray.width > 900) 900f / gray.width else 1f
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(gray, (gray.width * scale).toInt(), (gray.height * scale).toInt(), true)
        else gray

        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val threshold = otsuThreshold(px)

        var bestAngle = 0f
        var bestScore = scoreRows(small, threshold)
        var a = -4f
        val buf = IntArray(w * h)
        while (a <= 4f) {
            if (a != 0f) {
                val r = rotateWhite(small, a)
                val s = scoreRows(r, threshold, buf)
                if (s > bestScore) { bestScore = s; bestAngle = a }
                r.recycle()
            }
            a += 0.5f
        }
        if (small != gray) small.recycle()
        return bestAngle
    }

    /**
     * "Peakiness" of the horizontal dark-pixel projection: Σ(dark_row²) normalized by
     * total dark pixels. Normalizing removes the bias toward angles that simply keep
     * more ink on-screen (rotation pushes corner pixels into alpha=0 white), so the
     * winner is the angle with the sharpest row peaks — i.e. text horizontal.
     */
    private fun scoreRows(bmp: Bitmap, threshold: Int, reuse: IntArray? = null): Double {
        val w = bmp.width; val h = bmp.height
        val px = reuse ?: IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sumSq = 0.0
        var total = 0L
        for (y in 0 until h) {
            var dark = 0
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                if (Color.alpha(p) > 0 && luma(p) < threshold) dark++
            }
            sumSq += dark.toDouble() * dark
            total += dark
        }
        return if (total == 0L) 0.0 else sumSq / total
    }

    internal fun otsuThreshold(px: IntArray): Int {
        val hist = IntArray(256)
        for (p in px) if (Color.alpha(p) > 0) hist[luma(p)]++
        val total = hist.sum()
        if (total == 0) return 128
        var sum = 0.0
        for (t in 0..255) sum += t.toDouble() * hist[t]
        var sumB = 0.0; var wB = 0; var maxVar = 0.0; var thr = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; thr = t }
        }
        return thr
    }

    private fun otsu(gray: Bitmap): Bitmap {
        val w = gray.width; val h = gray.height
        val px = IntArray(w * h)
        gray.getPixels(px, 0, w, 0, 0, w, h)
        val thr = otsuThreshold(px)
        val black = Color.BLACK; val white = Color.WHITE
        for (i in px.indices) {
            px[i] = if (Color.alpha(px[i]) > 0 && luma(px[i]) < thr) black else white
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
