package com.pavlo.pdfer

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.LibraryRepository
import com.pavlo.pdfer.data.StoredElement
import com.pavlo.pdfer.data.StoredPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class LibraryLayoutCacheTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val repo = LibraryRepository(ctx)
    private val docId = "layouttest_doc"
    private val variant = "fast_e1"

    @After fun cleanup() {
        File(ctx.cacheDir, "ocr/$docId").deleteRecursively()
    }

    @Test fun textOnlyLayoutRoundTrips() {
        repo.saveLayout(docId, 0, variant, StoredPage(listOf(StoredElement(text = "hello"))))
        val got = repo.cachedLayout(docId, 0, variant)
        assertNotNull(got)
        assertEquals("hello", got!!.elements.single().text)
    }

    @Test fun variantsAreIsolated() {
        repo.saveLayout(docId, 1, "fast_e1", StoredPage(listOf(StoredElement(text = "a"))))
        assertNotNull(repo.cachedLayout(docId, 1, "fast_e1"))
        assertNull(repo.cachedLayout(docId, 1, "best_e1"))
    }

    @Test fun missingFigureFileInvalidatesCache() {
        val fig = repo.figureFile(docId, 2, variant, 0)
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        FileOutputStream(fig).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        repo.saveLayout(
            docId, 2, variant,
            StoredPage(listOf(StoredElement(image = fig.absolutePath, w = 4, h = 4))),
        )
        assertNotNull("cache valid while crop exists", repo.cachedLayout(docId, 2, variant))

        fig.delete()
        assertNull("cache must invalidate when a crop is missing", repo.cachedLayout(docId, 2, variant))
    }
}
