package com.pavlo.pdfer.data

import android.graphics.Rect
import kotlinx.serialization.Serializable

/**
 * A recognized paragraph with its bounding box (OCR bitmap pixel space).
 * [lineHeightPx] is the median height of its text lines (its glyph size); the caller
 * compares it against the page-wide median to decide whether it is a heading.
 * [heading] is a per-call estimate (one band only) — prefer a global decision via [lineHeightPx].
 */
data class TextPara(
    val box: Rect, val text: String, val heading: Boolean = false, val lineHeightPx: Int = 0,
)

/** One ordered piece of a reflowed page: text, a heading, a formula line, or a figure. */
sealed interface PageElement {
    data class Paragraph(val text: String) : PageElement
    /** A section/title line, rendered larger and bold. */
    data class Heading(val text: String) : PageElement
    /** A math formula kept as text, shown monospaced (not justified prose). */
    data class Formula(val text: String) : PageElement
    /** [path] is an absolute file path to the cropped image (original colors, deskewed). */
    data class Figure(val path: String, val widthPx: Int, val heightPx: Int) : PageElement
}

/** On-disk cache form of a page's layout (one entry per element, in reading order). */
@Serializable
data class StoredPage(val elements: List<StoredElement>)

@Serializable
data class StoredElement(
    val text: String? = null,
    val image: String? = null,   // absolute path to the cropped PNG
    val formula: Boolean = false,
    val heading: Boolean = false,
    val w: Int = 0,
    val h: Int = 0,
)
