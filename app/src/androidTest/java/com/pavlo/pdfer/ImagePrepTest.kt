package com.pavlo.pdfer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.ImagePrep
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePrepTest {

    @Test
    fun enhanceBinarizesTiltedLines() {
        val base = TestFixtures.ruledLinesBitmap()
        val tilted = TestFixtures.rotated(base, 3f)
        base.recycle()

        val enhanced = ImagePrep.enhance(tilted)
        try {
            assertNotNull(enhanced)
            assertTrue(enhanced.width > 0 && enhanced.height > 0)
            // Otsu binarization: virtually every opaque pixel should be near-black
            // or near-white.
            val frac = TestFixtures.nearBinaryFraction(enhanced)
            assertTrue("expected near-binary output, got fraction=$frac", frac > 0.98)
        } finally {
            if (!tilted.isRecycled) tilted.recycle()
            if (enhanced !== tilted) enhanced.recycle()
        }
    }

    @Test
    fun enhanceWithoutBinarizeStillReturnsBitmap() {
        val base = TestFixtures.ruledLinesBitmap(width = 600, height = 400)
        val out = ImagePrep.enhance(base, binarize = false)
        try {
            assertNotNull(out)
            assertTrue(out.width > 0 && out.height > 0)
        } finally {
            if (!base.isRecycled) base.recycle()
            if (out !== base) out.recycle()
        }
    }

    @Test
    fun enhanceOnBlankBitmapDoesNotCrash() {
        val blank = Bitmap.createBitmap(50, 40, Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.WHITE)
        val out = ImagePrep.enhance(blank)
        try {
            assertNotNull(out)
            assertTrue(out.width > 0 && out.height > 0)
        } finally {
            if (!blank.isRecycled) blank.recycle()
            if (out !== blank) out.recycle()
        }
    }

    @Test
    fun enhanceOnTinyBitmapDoesNotCrash() {
        val tiny = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tiny.eraseColor(android.graphics.Color.WHITE)
        val out = ImagePrep.enhance(tiny)
        try {
            assertNotNull(out)
        } finally {
            if (!tiny.isRecycled) tiny.recycle()
            if (out !== tiny) out.recycle()
        }
    }
}
