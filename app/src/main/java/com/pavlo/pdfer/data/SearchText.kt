package com.pavlo.pdfer.data

private val WHITESPACE = Regex("\\s+")

/**
 * Builds a search-result snippet around a match.
 *
 * Takes up to 40 characters of context on each side of the match (clamped to the
 * text bounds), collapses all runs of whitespace to single spaces, trims, and adds
 * a leading/trailing ellipsis when the snippet was cut off from the surrounding text.
 *
 * @param text the full text being searched
 * @param at   start index of the match within [text]
 * @param len  length of the match
 */
internal fun buildSnippet(text: String, at: Int, len: Int): String {
    val start = (at - 40).coerceAtLeast(0)
    val end = (at + len + 40).coerceAtMost(text.length)
    val raw = text.substring(start, end).replace(WHITESPACE, " ").trim()
    return (if (start > 0) "…" else "") + raw + (if (end < text.length) "…" else "")
}
