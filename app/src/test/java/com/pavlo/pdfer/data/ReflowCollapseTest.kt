package com.pavlo.pdfer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReflowCollapseTest {

    @Test fun joinsWrappedLinesWithSpaces() {
        assertEquals("alpha beta gamma", Reflow.collapse("alpha\nbeta\ngamma"))
    }

    @Test fun repairsEndOfLineHyphenation() {
        assertEquals("information", Reflow.collapse("infor-\nmation"))
    }

    @Test fun blankBlockCollapsesToEmpty() {
        assertEquals("", Reflow.collapse("   \n  \n"))
    }

    @Test fun separateBlocksJoinWithSingleSpace() {
        // "a\n\nb" -> paragraphs ["a","b"] -> joined "a b"
        assertEquals("a b", Reflow.collapse("a\n\nb"))
    }

    @Test fun crlfIsHandled() {
        assertEquals("one two", Reflow.collapse("one\r\ntwo"))
    }
}
