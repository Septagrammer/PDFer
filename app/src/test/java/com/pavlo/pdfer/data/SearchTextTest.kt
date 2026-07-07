package com.pavlo.pdfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    private val ellipsis = "…"

    @Test
    fun shortText_matchIsWholeString_noEllipsis() {
        val text = "hello world"
        val at = text.indexOf("world")
        val snippet = buildSnippet(text, at, "world".length)
        assertEquals("hello world", snippet)
        assertFalse(snippet.startsWith(ellipsis))
        assertFalse(snippet.endsWith(ellipsis))
    }

    @Test
    fun matchAtStart_noLeadingEllipsis() {
        val text = "needle then a long tail ".repeat(10)
        val snippet = buildSnippet(text, 0, "needle".length)
        assertFalse("must not start with ellipsis", snippet.startsWith(ellipsis))
        assertTrue("must end with ellipsis (tail truncated)", snippet.endsWith(ellipsis))
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun matchInMiddle_hasBothEllipses() {
        val prefix = "x".repeat(100)
        val suffix = "y".repeat(100)
        val text = prefix + "NEEDLE" + suffix
        val at = prefix.length
        val snippet = buildSnippet(text, at, "NEEDLE".length)
        assertTrue(snippet.startsWith(ellipsis))
        assertTrue(snippet.endsWith(ellipsis))
        assertTrue(snippet.contains("NEEDLE"))
    }

    @Test
    fun matchAtEnd_noTrailingEllipsis() {
        val text = "a long lead-in section of words before the needle"
        val at = text.indexOf("needle")
        val snippet = buildSnippet(text, at, "needle".length)
        assertTrue("must start with ellipsis (head truncated)", snippet.startsWith(ellipsis))
        assertFalse("must not end with ellipsis", snippet.endsWith(ellipsis))
        assertTrue(snippet.endsWith("needle"))
    }

    @Test
    fun whitespaceRuns_collapseToSingleSpace() {
        val text = "alpha   beta\t\tgamma\n\ndelta"
        val snippet = buildSnippet(text, 0, "alpha".length)
        assertEquals("alpha beta gamma delta", snippet)
    }

    @Test
    fun contextWindowIsBounded_to40CharsEachSide() {
        // 60 chars of context on each side; only 40 should survive (plus ellipses).
        val left = "L".repeat(60)
        val right = "R".repeat(60)
        val text = left + "M" + right
        val at = left.length
        val snippet = buildSnippet(text, at, 1)
        val stripped = snippet.removePrefix(ellipsis).removeSuffix(ellipsis)
        // 40 left + 1 match + 40 right = 81 retained characters
        assertEquals(81, stripped.length)
        assertEquals(ellipsis + "L".repeat(40) + "M" + "R".repeat(40) + ellipsis, snippet)
    }

    @Test
    fun leadingAndTrailingWhitespaceInWindow_isTrimmed() {
        // Spaces immediately inside the window edges get collapsed then trimmed.
        val text = "   trimmed match here   "
        val at = text.indexOf("match")
        val snippet = buildSnippet(text, at, "match".length)
        assertFalse(snippet.startsWith(" "))
        assertFalse(snippet.endsWith(" "))
        assertEquals("trimmed match here", snippet)
    }
}
