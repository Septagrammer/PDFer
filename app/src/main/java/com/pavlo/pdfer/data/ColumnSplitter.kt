package com.pavlo.pdfer.data

import android.graphics.Bitmap

/** Result of column analysis: a full-width header band on top (e.g. a spanning title)
 *  plus the ordered left-to-right column x-ranges below it. */
data class PageColumns(val headerBottomPx: Int, val columns: List<IntRange>)

/**
 * Detects text columns by finding tall, near-empty vertical gutters in a (binarized,
 * deskewed) page. OCR-ing each column separately and concatenating left-to-right
 * guarantees correct reading order — Tesseract's own column detection can read straight
 * across columns when their lines happen to align.
 *
 * A full-width title above the columns crosses the gutters, so it's reported as a
 * [PageColumns.headerBottomPx] band to be OCR'd whole (not chopped between columns).
 * Conservative: single-column pages are left whole.
 */
object ColumnSplitter {

    private const val TARGET_W = 1000
    private const val MIN_GUTTER_FRAC = 0.022f   // gutter must be at least this wide (of page width)
    private const val MIN_COL_FRAC = 0.06f       // ignore bands narrower than this (allows 4-6 cols)
    private const val MAX_COLUMNS = 8

    /** Ordered left-to-right column x-ranges (single range if one column). */
    fun columns(bmp: Bitmap): List<IntRange> = analyze(bmp).columns

    private fun luma(p: Int): Int =
        (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    fun analyze(bmp: Bitmap): PageColumns {
        val fullW = bmp.width; val fullH = bmp.height
        val whole = PageColumns(0, listOf(0 until fullW))
        if (fullW < 200) return whole

        val scale = if (fullW > TARGET_W) TARGET_W.toFloat() / fullW else 1f
        val w = (fullW * scale).toInt().coerceAtLeast(1)
        val h = (fullH * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != bmp) small.recycle()

        // Build the vertical ink profile over the BODY region only (skip the top ~18%, where
        // a full-width title lives, and the very bottom) so a spanning title doesn't fill the
        // gutters. A gutter is then a genuinely (near-)empty column.
        val yStart = (h * 0.18f).toInt()
        val yEnd = (h * 0.95f).toInt().coerceAtLeast(yStart + 1)
        val darkPerCol = IntArray(w)
        for (y in yStart until yEnd) {
            val row = y * w
            for (x in 0 until w) if (luma(px[row + x]) < 128) darkPerCol[x]++
        }
        // Smooth with a small max-filter so within-column gaps (between words/letters) fill
        // in, leaving only genuinely wide gutters as low points.
        val k = (w * 0.008f).toInt().coerceAtLeast(1)
        val prof = IntArray(w) { x ->
            var m = 0
            for (j in (x - k).coerceAtLeast(0)..(x + k).coerceAtMost(w - 1)) if (darkPerCol[j] > m) m = darkPerCol[j]
            m
        }
        // Relative threshold: auto-scales between sparse (few lines) and dense pages.
        val inkedVals = prof.filter { it > 0 }.sorted()
        if (inkedVals.size < w * 0.05f) return whole
        val typical = inkedVals[inkedVals.size / 2]
        val inkThr = (typical * 0.12f).coerceAtLeast(1f)
        val firstX = (0 until w).firstOrNull { prof[it] > inkThr } ?: return whole
        val lastX = (w - 1 downTo 0).first { prof[it] > inkThr }
        if (lastX - firstX < w * 0.3f) return whole

        val minGutter = (w * MIN_GUTTER_FRAC).toInt().coerceAtLeast(4)
        val bands = ArrayList<IntRange>()
        var bandStart = firstX
        var x = firstX
        while (x <= lastX) {
            if (prof[x] <= inkThr) {
                var g = x
                while (g <= lastX && prof[g] <= inkThr) g++
                if (g - x >= minGutter) {
                    if (x - 1 >= bandStart) bands.add(bandStart until x)
                    bandStart = g
                }
                x = g
            } else x++
        }
        if (bandStart <= lastX) bands.add(bandStart..lastX)

        val wide = bands.filter { it.last - it.first + 1 >= w * MIN_COL_FRAC }
        if (wide.size < 2 || wide.size > MAX_COLUMNS) return whole

        // Header band: top rows whose ink spans the gutters between columns (a full-width
        // title). Scan from the top; stop at the first sizeable run of column-only rows.
        val gutterXs = (firstX..lastX).filter { gx -> wide.none { gx in it } }
        var headerBottom = 0
        if (gutterXs.isNotEmpty()) {
            val limit = (h * 0.33f).toInt()
            var lastSpan = -1; var gap = 0; var y = 0
            while (y < limit) {
                val row = y * w
                val spanning = gutterXs.any { gx -> luma(px[row + gx]) < 128 }
                if (spanning) { lastSpan = y; gap = 0 } else if (lastSpan >= 0) {
                    gap++; if (gap > h * 0.04f) break
                }
                y++
            }
            if (lastSpan >= 0) headerBottom = (lastSpan + h * 0.012f).toInt().coerceAtMost(h - 1)
        }

        val invX = 1f / scale
        val invY = fullH.toFloat() / h
        val cols = wide.map {
            (it.first * invX).toInt().coerceIn(0, fullW - 1)..(it.last * invX).toInt().coerceIn(0, fullW - 1)
        }
        return PageColumns((headerBottom * invY).toInt(), cols)
    }
}
