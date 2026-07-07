package com.pavlo.pdfer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderSettingsTest {

    private fun variant(quality: OcrQuality, enhance: Boolean): String =
        ReaderSettings(quality = quality, enhance = enhance).ocrVariant

    @Test
    fun exactFormat_forEachCombination() {
        assertEquals("fast_e1", variant(OcrQuality.FAST, enhance = true))
        assertEquals("fast_e0", variant(OcrQuality.FAST, enhance = false))
        assertEquals("best_e1", variant(OcrQuality.BEST, enhance = true))
        assertEquals("best_e0", variant(OcrQuality.BEST, enhance = false))
    }

    @Test
    fun variantDiffersAcrossQuality() {
        assertNotEquals(
            variant(OcrQuality.FAST, enhance = true),
            variant(OcrQuality.BEST, enhance = true)
        )
        assertNotEquals(
            variant(OcrQuality.FAST, enhance = false),
            variant(OcrQuality.BEST, enhance = false)
        )
    }

    @Test
    fun variantDiffersAcrossEnhance() {
        assertNotEquals(
            variant(OcrQuality.FAST, enhance = true),
            variant(OcrQuality.FAST, enhance = false)
        )
        assertNotEquals(
            variant(OcrQuality.BEST, enhance = true),
            variant(OcrQuality.BEST, enhance = false)
        )
    }

    @Test
    fun allFourCombinationsAreDistinct() {
        val variants = setOf(
            variant(OcrQuality.FAST, enhance = true),
            variant(OcrQuality.FAST, enhance = false),
            variant(OcrQuality.BEST, enhance = true),
            variant(OcrQuality.BEST, enhance = false),
        )
        assertEquals(4, variants.size)
    }

    @Test
    fun variantIsStableForSameInputs() {
        val a = ReaderSettings(quality = OcrQuality.BEST, enhance = false)
        val b = ReaderSettings(quality = OcrQuality.BEST, enhance = false)
        assertEquals(a.ocrVariant, b.ocrVariant)
        // recomputed property is deterministic across reads
        assertEquals(a.ocrVariant, a.ocrVariant)
    }

    @Test
    fun variantIgnoresUnrelatedSettings() {
        // Only quality + enhance contribute to the cache key.
        val base = ReaderSettings(quality = OcrQuality.FAST, enhance = true)
        val tweaked = base.copy(
            fontSizeSp = 42,
            lineHeightMult = 2.0f,
            serif = false,
            theme = ReaderTheme.DARK,
        )
        assertEquals(base.ocrVariant, tweaked.ocrVariant)
    }

    @Test
    fun defaultSettings_useFastEnhanced() {
        assertEquals("fast_e1", ReaderSettings().ocrVariant)
    }
}
