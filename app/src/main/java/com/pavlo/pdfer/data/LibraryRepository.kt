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
import java.security.MessageDigest

/**
 * Owns imported documents, the recents list, and the per-page OCR text cache.
 * All on-disk, no database — the data set is small and simple.
 */
class LibraryRepository(private val app: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val docsDir = File(app.filesDir, "docs").apply { mkdirs() }
    private val ocrDir = File(app.cacheDir, "ocr").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "library.json")

    private val _docs = MutableStateFlow(loadIndex())
    val docs: StateFlow<List<DocumentRef>> = _docs.asStateFlow()

    private fun loadIndex(): List<DocumentRef> =
        runCatching { json.decodeFromString<List<DocumentRef>>(indexFile.readText()) }
            .getOrDefault(emptyList())

    private fun persist(list: List<DocumentRef>) {
        _docs.value = list
        runCatching { indexFile.writeText(json.encodeToString(list)) }
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
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); digest.update(buf, 0, n)
                }
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(24)

        byId(id)?.let { existing ->
            tmp.delete()
            touch(existing.id, nowMs)
            return@withContext existing
        }

        val fileName = "$id.pdf"
        tmp.copyTo(File(docsDir, fileName), overwrite = true)
        tmp.delete()

        val pageCount = PdfPageSource(File(docsDir, fileName)).use { it.pageCount }
        val doc = DocumentRef(
            id = id,
            title = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
            fileName = fileName,
            pageCount = pageCount,
            addedAt = nowMs,
            lastOpenedAt = nowMs,
        )
        persist(listOf(doc) + _docs.value)
        doc
    }

    fun touch(id: String, nowMs: Long) {
        persist(_docs.value.map { if (it.id == id) it.copy(lastOpenedAt = nowMs) else it })
    }

    fun saveLastPage(id: String, page: Int, nowMs: Long) {
        persist(_docs.value.map {
            if (it.id == id) it.copy(lastPage = page, lastOpenedAt = nowMs) else it
        })
    }

    suspend fun delete(doc: DocumentRef) = withContext(Dispatchers.IO) {
        file(doc).delete()
        File(ocrDir, doc.id).deleteRecursively()
        persist(_docs.value.filterNot { it.id == doc.id })
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
