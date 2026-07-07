package com.pavlo.pdfer.data

/**
 * Turns raw Tesseract output into clean, reflowable paragraphs.
 *
 * Tesseract (PSM_AUTO) already orders text across columns and separates blocks
 * with blank lines. We collapse the hard line wraps inside each paragraph so the
 * UI can reflow text to any width, and we repair end-of-line hyphenation.
 */
object Reflow {

    /** Collapse one Tesseract paragraph (with hard line wraps) into a single flowing string. */
    fun collapse(block: String): String = paragraphs(block).joinToString(" ")

    fun paragraphs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val lines = raw.replace("\r\n", "\n").split("\n")
        val paras = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val p = current.toString().trim()
            if (p.isNotEmpty()) paras += p
            current.clear()
        }

        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) {
                flush()
                continue
            }
            if (current.isEmpty()) {
                current.append(t)
            } else {
                val prev = current.toString()
                if (prev.endsWith("-") && prev.length >= 2 && prev[prev.length - 2].isLetter()) {
                    // word split across lines: "infor-\nmation" -> "information"
                    current.setLength(current.length - 1)
                    current.append(t)
                } else {
                    current.append(' ').append(t)
                }
            }
        }
        flush()
        return paras
    }
}
