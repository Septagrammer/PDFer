package com.pavlo.pdfer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pavlo.pdfer.data.DocumentRef
import com.pavlo.pdfer.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun newPdfFile(): File {
        val f = File.createTempFile("libtest", ".pdf", ctx.cacheDir)
        TestFixtures.writeOnePagePdf(f)
        return f
    }

    @Test
    fun importRegistersDocumentAndExposesViaFlow() {
        val repo = LibraryRepository(ctx)
        val src = newPdfFile()
        var doc: DocumentRef? = null
        try {
            doc = runBlocking { repo.import(Uri.fromFile(src), nowMs = 1_000L) }
            val d = doc!!
            assertEquals("single page PDF", 1, d.pageCount)
            assertTrue("id must be non-empty", d.id.isNotEmpty())
            assertTrue("flow must contain the imported doc", repo.docs.value.any { it.id == d.id })
            assertTrue("backing file must exist", repo.file(d).exists())
        } finally {
            src.delete()
            doc?.let { runBlocking { repo.delete(it) } }
        }
    }

    @Test
    fun ocrTextCacheIsVariantIsolated() {
        val repo = LibraryRepository(ctx)
        val src = newPdfFile()
        var doc: DocumentRef? = null
        try {
            doc = runBlocking { repo.import(Uri.fromFile(src), nowMs = 2_000L) }
            val id = doc!!.id

            repo.saveText(id, page = 0, variant = "fast_e1", text = "hello")
            assertEquals("hello", repo.cachedText(id, 0, "fast_e1"))
            // A different variant key must not see the same cache entry.
            assertNull(repo.cachedText(id, 0, "best_e1"))
            // A different page must not see it either.
            assertNull(repo.cachedText(id, 1, "fast_e1"))
        } finally {
            src.delete()
            doc?.let { runBlocking { repo.delete(it) } }
        }
    }

    @Test
    fun saveLastPageUpdatesDoc() {
        val repo = LibraryRepository(ctx)
        val src = newPdfFile()
        var doc: DocumentRef? = null
        try {
            doc = runBlocking { repo.import(Uri.fromFile(src), nowMs = 3_000L) }
            val id = doc!!.id
            assertEquals(0, repo.byId(id)?.lastPage)

            repo.saveLastPage(id, page = 5, nowMs = 4_000L)
            assertEquals(5, repo.byId(id)?.lastPage)
            assertEquals(4_000L, repo.byId(id)?.lastOpenedAt)
        } finally {
            src.delete()
            doc?.let { runBlocking { repo.delete(it) } }
        }
    }

    @Test
    fun reimportingSameContentReusesId() {
        val repo = LibraryRepository(ctx)
        val src = newPdfFile()
        var doc: DocumentRef? = null
        try {
            val first = runBlocking { repo.import(Uri.fromFile(src), nowMs = 5_000L) }
            val second = runBlocking { repo.import(Uri.fromFile(src), nowMs = 6_000L) }
            doc = second
            // Same bytes -> same SHA-based id, and not a duplicate entry.
            assertEquals(first.id, second.id)
            assertEquals(1, repo.docs.value.count { it.id == first.id })
        } finally {
            src.delete()
            doc?.let { runBlocking { repo.delete(it) } }
        }
    }

    @Test
    fun deleteRemovesFromFlowAndDeletesFile() {
        val repo = LibraryRepository(ctx)
        val src = newPdfFile()
        try {
            val doc = runBlocking { repo.import(Uri.fromFile(src), nowMs = 7_000L) }
            val file = repo.file(doc)
            assertTrue(file.exists())

            runBlocking { repo.delete(doc) }

            assertFalse("doc must be gone from flow", repo.docs.value.any { it.id == doc.id })
            assertFalse("backing file must be deleted", file.exists())
        } finally {
            src.delete()
        }
    }
}
