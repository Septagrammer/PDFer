package com.pavlo.pdfer.data

enum class Script { NORMAL, SUPER, SUB }

/** A run of formula text at a baseline level (normal / superscript / subscript). */
data class MathSegment(val text: String, val script: Script)

/**
 * Heuristic classifier: does a recognized line look like a math formula rather than
 * prose? Used to (a) re-OCR the region with the math model and (b) render it as
 * monospaced text instead of justified prose. Pure logic, unit-tested.
 */
object MathText {

    // Operators / relations / common math glyphs (besides plain brackets & slash).
    private const val SYMBOLS =
        "=+−*×÷^√∫∑∏∂≈≅≤≥≠≡±∓·∞∝→←↔αβγδεζηθικλμνξπρστυφχψωΓΔΘΛΞΠΣΦΨΩ²³¹⁰⁴⁵⁶⁷⁸⁹ⁿₐₓ₀₁₂₃₄₅"
    private const val BRACKETS = "()[]{}<>|/"

    fun isFormulaLike(s: String): Boolean {
        val t = s.trim()
        if (t.length < 3) return false
        var letters = 0; var digits = 0; var sym = 0
        for (c in t) when {
            c.isLetter() -> letters++
            c.isDigit() -> digits++
            c in SYMBOLS || c in BRACKETS -> sym++
        }
        if (sym == 0) return false
        val total = letters + digits + sym
        if (total == 0) return false
        val nonAlpha = (digits + sym).toFloat() / total
        // Prose words = runs of 3+ letters; formulas have very few of these.
        val words = t.split(Regex("\\s+")).count { w -> w.length >= 3 && w.all { it.isLetter() } }
        return (nonAlpha >= 0.30f && words <= 5) ||
            (t.contains('=') && nonAlpha >= 0.22f && words <= 6)
    }

    /** Turn multiplication asterisks into a tight middle dot: "a * b" / "a*b" -> "a·b". */
    fun withMultiplicationDots(s: String): String = s.replace(Regex("\\s*\\*\\s*"), "·")

    /**
     * Split a formula into baseline segments so `^`/`_` render as real super/subscripts
     * (e.g. "a^2" -> "a" + superscript "2"). The exponent/index token after `^`/`_` is a
     * `{...}` group, a `(...)` group, a run of digits, or a single character.
     */
    // Apostrophes/primes that scanners commonly mistake a caret "^" for.
    private const val PRIMES = "'’′´`"

    /**
     * @param primeAsSuper when true (formula context), a prime/apostrophe directly
     *   followed by a digit is treated as a misread caret (e.g. "b'2" -> b superscript 2).
     *   Off for prose so "'90s" and derivative primes ("f'") stay literal.
     */
    fun toSegments(s: String, primeAsSuper: Boolean = false): List<MathSegment> {
        val out = ArrayList<MathSegment>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out += MathSegment(buf.toString(), Script.NORMAL); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hasNext = i + 1 < s.length
            val isMisreadCaret = primeAsSuper && c in PRIMES && hasNext && s[i + 1].isDigit()
            val isMarker = c == '^' || c == '_' || isMisreadCaret
            if (isMarker && hasNext && !s[i + 1].isWhitespace()) {
                val token = readToken(s, i + 1)
                if (token != null) {
                    flush()
                    out += MathSegment(token.first, if (c == '_') Script.SUB else Script.SUPER)
                    i = token.second
                    continue
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    private fun readToken(s: String, start: Int): Pair<String, Int>? = when (s[start]) {
        '{' -> s.indexOf('}', start + 1).let { if (it < 0) null else s.substring(start + 1, it) to (it + 1) }
        '(' -> matchParen(s, start).let { if (it < 0) null else s.substring(start, it + 1) to (it + 1) }
        else -> when {
            s[start].isDigit() -> {
                var j = start; while (j < s.length && s[j].isDigit()) j++
                s.substring(start, j) to j
            }
            s[start].isLetter() -> s[start].toString() to (start + 1)
            else -> null
        }
    }

    private fun matchParen(s: String, open: Int): Int {
        var depth = 0
        for (j in open until s.length) when (s[j]) {
            '(' -> depth++
            ')' -> { depth--; if (depth == 0) return j }
        }
        return -1
    }
}
