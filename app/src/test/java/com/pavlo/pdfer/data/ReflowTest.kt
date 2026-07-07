package com.pavlo.pdfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowTest {

    @Test
    fun blankInput_returnsEmptyList() {
        assertEquals(emptyList<String>(), Reflow.paragraphs(""))
        assertEquals(emptyList<String>(), Reflow.paragraphs("   "))
        assertEquals(emptyList<String>(), Reflow.paragraphs("\n\n  \n\t\n"))
    }

    @Test
    fun singleParagraphWrappedAcrossLines_collapsesToOne() {
        val raw = "The quick brown fox\njumps over the\nlazy dog"
        val paras = Reflow.paragraphs(raw)
        assertEquals(listOf("The quick brown fox jumps over the lazy dog"), paras)
    }

    @Test
    fun blankLineSeparatesParagraphs() {
        val raw = "First paragraph line one\nstill first paragraph\n\nSecond paragraph here"
        val paras = Reflow.paragraphs(raw)
        assertEquals(
            listOf("First paragraph line one still first paragraph", "Second paragraph here"),
            paras
        )
    }

    @Test
    fun endOfLineHyphenation_isRepaired() {
        val raw = "infor-\nmation"
        assertEquals(listOf("information"), Reflow.paragraphs(raw))
    }

    @Test
    fun hyphenationRepair_doesNotInsertSpace() {
        val raw = "self-aware sys-\ntem"
        // "self-aware" keeps its real hyphen (not at line end); "sys-\ntem" -> "system"
        assertEquals(listOf("self-aware system"), Reflow.paragraphs(raw))
    }

    @Test
    fun hyphenAtLineEndWithNonLetterBefore_isNotMerged() {
        // The char before the trailing hyphen is a space, not a letter, so this is a
        // dash, not a split word — lines join with a space and the hyphen stays.
        val raw = "a -\nb"
        val paras = Reflow.paragraphs(raw)
        assertEquals(listOf("a - b"), paras)
        assertTrue(paras.single().contains("- b"))
    }

    @Test
    fun hyphenAtLineEndWithDigitBefore_isNotMerged() {
        // Digit before hyphen is not a letter -> treated as a real dash, not a word split.
        val raw = "page 12-\n34"
        assertEquals(listOf("page 12- 34"), Reflow.paragraphs(raw))
    }

    @Test
    fun crlfLineEndings_handledLikeLf() {
        val raw = "line one\r\nline two\r\n\r\nsecond para"
        val paras = Reflow.paragraphs(raw)
        assertEquals(listOf("line one line two", "second para"), paras)
    }

    @Test
    fun crlfHyphenation_isRepaired() {
        val raw = "infor-\r\nmation"
        assertEquals(listOf("information"), Reflow.paragraphs(raw))
    }

    @Test
    fun leadingAndTrailingWhitespacePerLine_isTrimmed() {
        val raw = "   hello   \n   world   "
        assertEquals(listOf("hello world"), Reflow.paragraphs(raw))
    }

    @Test
    fun leadingAndTrailingBlankLines_areIgnored() {
        val raw = "\n\n  \nactual content\n  \n\n"
        assertEquals(listOf("actual content"), Reflow.paragraphs(raw))
    }

    @Test
    fun multipleConsecutiveBlankLines_collapseToSingleSeparator() {
        val raw = "para one\n\n\n\npara two"
        val paras = Reflow.paragraphs(raw)
        assertEquals(listOf("para one", "para two"), paras)
    }

    @Test
    fun whitespaceOnlyLinesBetweenParagraphs_actAsSeparators() {
        val raw = "para one\n   \n\t\npara two"
        assertEquals(listOf("para one", "para two"), Reflow.paragraphs(raw))
    }

    @Test
    fun singleHyphenLine_isNotTreatedAsWordSplit() {
        // A line that is just "-" has length 1, so the length >= 2 guard prevents merge.
        val raw = "a\n-\nb"
        assertEquals(listOf("a - b"), Reflow.paragraphs(raw))
    }
}
