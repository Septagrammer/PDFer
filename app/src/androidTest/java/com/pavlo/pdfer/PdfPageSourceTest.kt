package com.pavlo.pdfer

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.PdfPageSource
import com.pavlo.pdfer.data.downscaled
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPageSourceTest {

    private lateinit var pdf: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        pdf = File.createTempFile("pps", ".pdf", ctx.cacheDir)
        TestFixtures.writeOnePagePdf(pdf)
    }

    @After
    fun tearDown() {
        pdf.delete()
    }

    @Test
    fun pageCountIsOne() {
        PdfPageSource(pdf).use { src ->
            assertEquals(1, src.pageCount)
        }
    }

    @Test
    fun renderReturnsSensibleBitmap() {
        PdfPageSource(pdf).use { src ->
            // Letter page at 300 dpi -> ~2550 x 3300, under the 3000 default cap on
            // width but the height (3300) exceeds it, so it should be capped.
            val bmp = src.render(0)
            try {
                assertNotNull(bmp)
                assertTrue("width > 0", bmp.width > 0)
                assertTrue("height > 0", bmp.height > 0)
                assertTrue(
                    "longest side must respect default maxPx cap",
                    maxOf(bmp.width, bmp.height) <= 3000,
                )
            } finally {
                bmp.recycle()
            }
        }
    }

    @Test
    fun renderRespectsExplicitMaxPx() {
        PdfPageSource(pdf).use { src ->
            val maxPx = 500
            val bmp = src.render(0, targetDpi = 300, maxPx = maxPx)
            try {
                assertTrue(
                    "longest side ${maxOf(bmp.width, bmp.height)} must be <= $maxPx",
                    maxOf(bmp.width, bmp.height) <= maxPx,
                )
                // Letter aspect ratio 612:792 should be preserved (portrait).
                assertTrue("portrait preserved", bmp.height >= bmp.width)
            } finally {
                bmp.recycle()
            }
        }
    }

    @Test
    fun lowDpiYieldsSmallerBitmapThanCap() {
        // At a low dpi the cap is irrelevant; size is driven by targetDpi.
        PdfPageSource(pdf).use { src ->
            val low = src.render(0, targetDpi = 72, maxPx = 3000)
            try {
                // 72 dpi on a 612x792pt page -> ~612x792 px.
                assertTrue(low.width in 1..900)
                assertTrue(low.height in 1..1100)
            } finally {
                low.recycle()
            }
        }
    }

    @Test
    fun downscaledIsNoOpWhenAlreadySmall() {
        val small = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        try {
            val result = small.downscaled(500)
            assertSame("must return the same instance when no scaling needed", small, result)
            assertEquals(100, result.width)
            assertEquals(80, result.height)
        } finally {
            small.recycle()
        }
    }

    @Test
    fun downscaledScalesDownAndPreservesAspect() {
        val big = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val result = big.downscaled(500)
        try {
            assertTrue("must produce a new bitmap", result !== big)
            assertEquals("longest side capped", 500, maxOf(result.width, result.height))
            // 2:1 aspect ratio preserved -> 500 x 250.
            assertEquals(500, result.width)
            assertEquals(250, result.height)
        } finally {
            result.recycle()
            // big is recycled by downscaled() itself; recycling twice is safe-guarded.
            if (!big.isRecycled) big.recycle()
        }
    }
}
