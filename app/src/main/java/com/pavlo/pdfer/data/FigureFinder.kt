package com.pavlo.pdfer.data

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Finds figure/photo regions on a (deskewed) page so they can be cropped and shown
 * in original colors instead of being OCR'd into garbage.
 *
 * Pure-Kotlin, no Tesseract block typing (which Tesseract4Android doesn't expose).
 * A pixel is "figure content" when it is OUTSIDE every recognized text box AND is
 * either dark ink (line-art / diagrams) or mid-gray continuous tone (photos). Content
 * is grouped on a coarse cell grid; only large, densely-filled blocks survive — so
 * sparse leftover text strokes don't get mistaken for an image.
 */
object FigureFinder {

    private const val TARGET_W = 1000     // downscale width for analysis
    private const val CELL = 12           // grid cell size in downscaled px
    private const val CELL_FILL = 0.22f   // content fraction to call a cell "filled"
    private const val MIN_AREA_FRAC = 0.02f
    private const val MIN_FILL = 0.18f    // filled-cell density inside a block's bbox
    private const val PAD = 3             // padding (downscaled px) around text boxes
    private const val MID_LOW = 60        // mid-gray (continuous-tone) band
    private const val MID_HIGH = 205

    fun find(color: Bitmap, textBoxes: List<Rect>): List<Rect> {
        val fullW = color.width; val fullH = color.height
        if (fullW < 40 || fullH < 40) return emptyList()
        val scale = if (fullW > TARGET_W) TARGET_W.toFloat() / fullW else 1f
        val w = (fullW * scale).toInt().coerceAtLeast(1)
        val h = (fullH * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(color, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != color) small.recycle()

        val inText = BooleanArray(w * h)
        for (b in textBoxes) {
            val l = (b.left * scale).toInt() - PAD
            val t = (b.top * scale).toInt() - PAD
            val r = (b.right * scale).toInt() + PAD
            val bo = (b.bottom * scale).toInt() + PAD
            for (y in t.coerceAtLeast(0) until bo.coerceAtMost(h)) {
                val row = y * w
                for (x in l.coerceAtLeast(0) until r.coerceAtMost(w)) inText[row + x] = true
            }
        }

        val thr = ImagePrep.otsuThreshold(px)
        val content = BooleanArray(w * h)
        for (i in px.indices) {
            if (inText[i]) continue
            val l = ImagePrep.luma(px[i])
            content[i] = l < thr || (l in MID_LOW..MID_HIGH)
        }

        val gw = (w + CELL - 1) / CELL
        val gh = (h + CELL - 1) / CELL
        val filled = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var cnt = 0; var tot = 0
                val y1 = ((gy + 1) * CELL).coerceAtMost(h)
                val x1 = ((gx + 1) * CELL).coerceAtMost(w)
                for (y in gy * CELL until y1) {
                    val row = y * w
                    for (x in gx * CELL until x1) { tot++; if (content[row + x]) cnt++ }
                }
                if (tot > 0 && cnt.toFloat() / tot >= CELL_FILL) filled[gy * gw + gx] = true
            }
        }

        // Connected components on the cell grid (8-connectivity).
        val comp = IntArray(gw * gh) { -1 }
        val rects = ArrayList<Rect>()
        val stack = ArrayDeque<Int>()
        var id = 0
        for (startCell in filled.indices) {
            if (!filled[startCell] || comp[startCell] != -1) continue
            comp[startCell] = id
            stack.addLast(startCell)
            var minX = gw; var minY = gh; var maxX = 0; var maxY = 0; var cells = 0
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                val cx = cur % gw; val cy = cur / gw
                cells++
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val ni = ny * gw + nx
                    if (filled[ni] && comp[ni] == -1) { comp[ni] = id; stack.addLast(ni) }
                }
            }
            id++

            val bboxCells = (maxX - minX + 1) * (maxY - minY + 1)
            if (cells.toFloat() / bboxCells < MIN_FILL) continue
            val pxX0 = minX * CELL; val pxY0 = minY * CELL
            val pxX1 = ((maxX + 1) * CELL).coerceAtMost(w); val pxY1 = ((maxY + 1) * CELL).coerceAtMost(h)
            if ((pxX1 - pxX0).toLong() * (pxY1 - pxY0) / (w.toFloat() * h) < MIN_AREA_FRAC) continue
            val fx0 = (pxX0 / scale).toInt().coerceIn(0, fullW)
            val fy0 = (pxY0 / scale).toInt().coerceIn(0, fullH)
            val fx1 = (pxX1 / scale).toInt().coerceIn(0, fullW)
            val fy1 = (pxY1 / scale).toInt().coerceIn(0, fullH)
            if (fx1 - fx0 < 24 || fy1 - fy0 < 24) continue
            rects.add(Rect(fx0, fy0, fx1, fy1))
        }
        return mergeOverlapping(rects)
    }

    private fun mergeOverlapping(rects: List<Rect>): List<Rect> {
        val out = rects.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) {
                for (j in i + 1 until out.size) {
                    if (Rect.intersects(out[i], out[j]) || closeBy(out[i], out[j])) {
                        out[i] = Rect(out[i]).apply { union(out[j]) }
                        out.removeAt(j); merged = true; break@loop
                    }
                }
            }
        }
        return out
    }

    private fun closeBy(a: Rect, b: Rect): Boolean =
        Rect.intersects(Rect(a).apply { inset(-12, -12) }, b)
}
