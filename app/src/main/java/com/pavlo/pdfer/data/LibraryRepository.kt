package com.pavlo.pdfer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns imported documents, the recents list, and the per-page OCR text cache.
 * All on-disk, no database — the data set is small and simple.
 */
class LibraryRepository(private val app: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val docsDir = File(app.filesDir, "docs").apply { mkdirs() }
    private val ocrDir = File(app.cacheDir, "ocr").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "library.json")
    private val mutationMutex = Mutex()

    private val _docs = MutableStateFlow(loadIndex())
    val docs: StateFlow<List<DocumentRef>> = _docs.asStateFlow()

    private fun loadIndex(): List<DocumentRef> =
        runCatching { json.decodeFromString<List<DocumentRef>>(indexFile.readText()) }
            .getOrDefault(emptyList())

    /**
     * Commits the authoritative library index before publishing it to the UI.
     * Call only while [mutationMutex] is held.
     */
    private fun persistLocked(list: List<DocumentRef>) {
        check(list.map { it.id }.distinct().size == list.size) {
            "Library index must not contain duplicate document ids"
        }
        val encoded = json.encodeToString(list).encodeToByteArray()
        val tmp = File.createTempFile("library", ".new", indexFile.parentFile)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(encoded)
                out.fd.sync()
            }
            // Reject a bad serialization before replacing the last known-good index.
            json.decodeFromString<List<DocumentRef>>(tmp.readText())
            if (!tmp.renameTo(indexFile)) throw IOException("Could not replace the library index")
            _docs.value = list
        } finally {
            tmp.delete()
        }
    }

    fun file(doc: DocumentRef): File = File(docsDir, doc.fileName)

    fun byId(id: String): DocumentRef? = _docs.value.firstOrNull { it.id == id }

    /** Copy a picked PDF into app storage and register it. Returns its id. */
    suspend fun import(uri: Uri, nowMs: Long): DocumentRef = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val displayName = resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "document.pdf"

        // Hash the content so re-importing the same file reuses its id and cache.
        val tmp = File.createTempFile("import", ".pdf", app.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri)
                ?: throw IOException("The selected document is no longer available")
            input.use {
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)
            // Validate the temporary copy before it can become library state.
            val pageCount = PdfPageSource(tmp).use { it.pageCount }

            mutationMutex.withLock {
                _docs.value.firstOrNull { it.id == id }?.let { existing ->
                    val refreshed = existing.copy(lastOpenedAt = maxOf(existing.lastOpenedAt, nowMs))
                    persistLocked(_docs.value.map { if (it.id == id) refreshed else it })
                    return@withLock refreshed
                }

                val fileName = "$id.pdf"
                val destination = File(docsDir, fileName)
                tmp.inputStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output, 64 * 1024)
                        output.fd.sync()
                    }
                }
                if (destination.length() != tmp.length()) {
                    throw IOException("The imported PDF was copied incompletely")
                }
                val doc = DocumentRef(
                    id = id,
                    title = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
                    fileName = fileName,
                    pageCount = pageCount,
                    addedAt = nowMs,
                    lastOpenedAt = nowMs,
                )
                persistLocked(listOf(doc) + _docs.value)
                doc
            }
        } finally {
            tmp.delete()
        }
    }

    suspend fun touch(id: String, nowMs: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val existing = _docs.value.firstOrNull { it.id == id } ?: return@withLock
            if (nowMs < existing.lastOpenedAt) return@withLock
            persistLocked(_docs.value.map { if (it.id == id) it.copy(lastOpenedAt = nowMs) else it })
        }
    }

    suspend fun saveLastPage(id: String, page: Int, nowMs: Long) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val existing = _docs.value.firstOrNull { it.id == id } ?: return@withLock
            if (nowMs < existing.lastOpenedAt) return@withLock
            val validPage = page.coerceIn(0, (existing.pageCount - 1).coerceAtLeast(0))
            persistLocked(_docs.value.map {
                if (it.id == id) it.copy(lastPage = validPage, lastOpenedAt = nowMs) else it
            })
        }
    }

    suspend fun delete(doc: DocumentRef) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val existing = _docs.value.firstOrNull { it.id == doc.id } ?: return@withLock
            // The logical deletion is durable before cleanup. A failed cleanup can leave
            // only orphaned files, never an index entry that points to deleted content.
            persistLocked(_docs.value.filterNot { it.id == doc.id })
            file(existing).delete()
            File(ocrDir, existing.id).deleteRecursively()
        }
    }

    // --- OCR text cache (one file per page + OCR variant) ---

    private fun pageCacheFile(docId: String, page: Int, variant: String) =
        File(File(ocrDir, docId).apply { mkdirs() }, "${page}_$variant.txt")

    fun cachedText(docId: String, page: Int, variant: String): String? =
        pageCacheFile(docId, page, variant).takeIf { it.exists() }?.readText()

    fun saveText(docId: String, page: Int, variant: String, text: String) {
        runCatching { pageCacheFile(docId, page, variant).writeText(text) }
    }

    // --- page layout cache (paragraphs + figure crops, in reading order) ---

    private fun ocrSub(docId: String) = File(ocrDir, docId).apply { mkdirs() }
    private fun layoutFile(docId: String, page: Int, variant: String) =
        File(ocrSub(docId), "${page}_$variant.json")

    /** File path to write/read the crop of figure [idx] for a page. */
    fun figureFile(docId: String, page: Int, variant: String, idx: Int): File =
        File(ocrSub(docId), "${page}_${variant}_img$idx.png")

    /** Returns the cached layout, or null if absent or any referenced crop is missing. */
    fun cachedLayout(docId: String, page: Int, variant: String): StoredPage? {
        val f = layoutFile(docId, page, variant)
        if (!f.exists()) return null
        val sp = runCatching { json.decodeFromString<StoredPage>(f.readText()) }.getOrNull()
            ?: return null
        if (sp.elements.any { it.image != null && !File(it.image).exists() }) return null
        return sp
    }

    fun saveLayout(docId: String, page: Int, variant: String, layout: StoredPage) {
        runCatching { layoutFile(docId, page, variant).writeText(json.encodeToString(layout)) }
    }
}
