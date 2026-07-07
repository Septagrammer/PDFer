package com.pavlo.pdfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.ReaderSettings
import com.pavlo.pdfer.data.ReaderTheme
import com.pavlo.pdfer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun updateThenReadRoundTrips() {
        val store = SettingsStore(ctx)
        // Values deliberately distinct from the defaults so the assertion is
        // meaningful even though DataStore is a process singleton.
        val written = ReaderSettings(
            fontSizeSp = 27,
            lineHeightMult = 2.1f,
            serif = false,
            theme = ReaderTheme.DARK,
            quality = OcrQuality.BEST,
            enhance = false,
        )
        runBlocking {
            store.update(written)
            val read = store.settings.first()
            assertEquals(27, read.fontSizeSp)
            assertEquals(2.1f, read.lineHeightMult, 0.0001f)
            assertEquals(false, read.serif)
            assertEquals(ReaderTheme.DARK, read.theme)
            assertEquals(OcrQuality.BEST, read.quality)
            assertEquals(false, read.enhance)
        }
    }

    @Test
    fun secondUpdateOverwritesFirst() {
        val store = SettingsStore(ctx)
        runBlocking {
            store.update(
                ReaderSettings(fontSizeSp = 12, theme = ReaderTheme.LIGHT, quality = OcrQuality.FAST)
            )
            store.update(
                ReaderSettings(fontSizeSp = 33, theme = ReaderTheme.SEPIA, quality = OcrQuality.BEST)
            )
            val read = store.settings.first()
            assertEquals(33, read.fontSizeSp)
            assertEquals(ReaderTheme.SEPIA, read.theme)
            assertEquals(OcrQuality.BEST, read.quality)
        }
    }

    @Test
    fun ocrVariantKeyReflectsQualityAndEnhance() {
        // Pure-logic guard on the cache key derivation used across the app.
        assertEquals(
            "fast_e1",
            ReaderSettings(quality = OcrQuality.FAST, enhance = true).ocrVariant
        )
        assertEquals(
            "best_e0",
            ReaderSettings(quality = OcrQuality.BEST, enhance = false).ocrVariant
        )
    }
}
