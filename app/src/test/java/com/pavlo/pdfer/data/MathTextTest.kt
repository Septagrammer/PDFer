package com.pavlo.pdfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {

    @Test fun detectsEquations() {
        assertTrue(MathText.isFormulaLike("E = mc^2"))
        assertTrue(MathText.isFormulaLike("a^2 + b^2 = c^2"))
        assertTrue(MathText.isFormulaLike("x = (-b ± √(b^2 - 4ac)) / 2a"))
        assertTrue(MathText.isFormulaLike("∫ f(x) dx = F(b) - F(a)"))
        assertTrue(MathText.isFormulaLike("Σ(i=1..n) i = n(n+1)/2"))
    }

    @Test fun rejectsProse() {
        assertFalse(MathText.isFormulaLike("This is a normal sentence about science."))
        assertFalse(MathText.isFormulaLike("See equation (3) below for details and context."))
        assertFalse(MathText.isFormulaLike("Привіт world, це звичайне речення."))
        assertFalse(MathText.isFormulaLike("Hello, world!"))
    }

    @Test fun rejectsEmptyAndTooShort() {
        assertFalse(MathText.isFormulaLike(""))
        assertFalse(MathText.isFormulaLike("  "))
        assertFalse(MathText.isFormulaLike("ok"))
    }

    @Test fun plainTextWithoutSymbolsIsNotFormula() {
        assertFalse(MathText.isFormulaLike("alpha beta gamma delta"))
    }

    // --- super/subscript segmentation ---

    private fun seg(t: String, s: Script) = MathSegment(t, s)

    @Test fun simplePowerBecomesSuperscript() {
        assertEquals(
            listOf(seg("a", Script.NORMAL), seg("2", Script.SUPER)),
            MathText.toSegments("a^2"),
        )
    }

    @Test fun multiplePowersInExpression() {
        assertEquals(
            listOf(
                seg("a", Script.NORMAL), seg("2", Script.SUPER),
                seg(" + b", Script.NORMAL), seg("2", Script.SUPER),
                seg(" = c", Script.NORMAL), seg("2", Script.SUPER),
            ),
            MathText.toSegments("a^2 + b^2 = c^2"),
        )
    }

    @Test fun multiDigitExponentAndBraces() {
        assertEquals(
            listOf(seg("10", Script.NORMAL), seg("23", Script.SUPER)),
            MathText.toSegments("10^23"),
        )
        assertEquals(
            listOf(seg("x", Script.NORMAL), seg("n+1", Script.SUPER)),
            MathText.toSegments("x^{n+1}"),
        )
    }

    @Test fun parenthesizedExponentKeepsParens() {
        assertEquals(
            listOf(seg("e", Script.NORMAL), seg("(i pi)", Script.SUPER)),
            MathText.toSegments("e^(i pi)"),
        )
    }

    @Test fun subscriptAndLetterExponent() {
        assertEquals(
            listOf(seg("m", Script.NORMAL), seg("1", Script.SUB)),
            MathText.toSegments("m_1"),
        )
        assertEquals(
            listOf(seg("x", Script.NORMAL), seg("n", Script.SUPER)),
            MathText.toSegments("x^n"),
        )
    }

    @Test fun loneCaretWithSpaceStaysLiteral() {
        assertEquals(listOf(seg("3 ^ 4", Script.NORMAL)), MathText.toSegments("3 ^ 4"))
    }

    @Test fun plainTextHasOneNormalSegment() {
        assertEquals(listOf(seg("hello world", Script.NORMAL)), MathText.toSegments("hello world"))
    }

    // --- prime mistaken for caret (formula mode only) ---

    @Test fun primeBeforeDigitIsPowerInFormulaMode() {
        assertEquals(
            listOf(seg("b", Script.NORMAL), seg("2", Script.SUPER)),
            MathText.toSegments("b'2", primeAsSuper = true),
        )
        // curly apostrophe / prime variants
        assertEquals(
            listOf(seg("b", Script.NORMAL), seg("2", Script.SUPER)),
            MathText.toSegments("b’2", primeAsSuper = true),
        )
    }

    @Test fun primeStaysLiteralInProseMode() {
        assertEquals(listOf(seg("the '90s", Script.NORMAL)), MathText.toSegments("the '90s"))
        assertEquals(listOf(seg("b'2", Script.NORMAL)), MathText.toSegments("b'2"))
    }

    @Test fun standalonePrimeNotConvertedEvenInFormulaMode() {
        // derivative prime f' (not followed by a digit) must stay literal
        assertEquals(listOf(seg("f'", Script.NORMAL)), MathText.toSegments("f'", primeAsSuper = true))
    }

    // --- multiplication dot (tight, no surrounding spaces) ---

    @Test fun multiplicationBecomesTightMiddleDot() {
        assertEquals("a·b", MathText.withMultiplicationDots("a * b"))
        assertEquals("a·b", MathText.withMultiplicationDots("a*b"))
        assertEquals("m·c", MathText.withMultiplicationDots("m  *  c"))
        assertEquals("G·(ml·112)", MathText.withMultiplicationDots("G* (ml * 112)"))
        assertEquals("E=m·c^2", MathText.withMultiplicationDots("E=m * c^2"))
    }

    @Test fun textWithoutAsteriskUnchanged() {
        assertEquals("hello world", MathText.withMultiplicationDots("hello world"))
    }
}
