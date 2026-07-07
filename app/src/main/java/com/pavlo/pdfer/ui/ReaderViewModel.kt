package com.pavlo.pdfer.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pavlo.pdfer.PdferApp
import com.pavlo.pdfer.data.ColumnSplitter
import com.pavlo.pdfer.data.DocumentRef
import com.pavlo.pdfer.data.FigureFinder
import com.pavlo.pdfer.data.ImagePrep
import com.pavlo.pdfer.data.LibraryRepository
import com.pavlo.pdfer.data.MathText
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.PageElement
import com.pavlo.pdfer.data.PdfPageSource
import com.pavlo.pdfer.data.ReaderSettings
import com.pavlo.pdfer.data.Reflow
import com.pavlo.pdfer.data.SettingsStore
import com.pavlo.pdfer.data.StoredElement
import com.pavlo.pdfer.data.StoredPage
import com.pavlo.pdfer.data.TessManager
import com.pavlo.pdfer.data.TextPara
import com.pavlo.pdfer.data.buildSnippet
import com.pavlo.pdfer.data.downscaled
import java.io.FileOutputStream
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface PageContent {
    data object Loading : PageContent
    data class Ready(val elements: List<PageElement>) : PageContent
    data class Empty(val message: String) : PageContent
    data class Error(val message: String) : PageContent
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val progress: Float) : DownloadState
    data object Done : DownloadState
    data object Failed : DownloadState
}

data class SearchHit(val page: Int, val snippet: String)

sealed interface SearchState {
    data object Idle : SearchState
    data class Indexing(val done: Int, val total: Int) : SearchState
    data class Results(val query: String, val hits: List<SearchHit>) : SearchState
}

class ReaderViewModel(
    private val library: LibraryRepository,
    private val tess: TessManager,
    private val settingsStore: SettingsStore,
    private val docId: String,
) : ViewModel() {

    val doc: DocumentRef? = library.byId(docId)
    private val source: PdfPageSource? =
        doc?.let { runCatching { PdfPageSource(library.file(it)) }.getOrNull() }

    val settings = settingsStore.settings
    val pageCount: Int get() = doc?.pageCount ?: 0

    private val _page = MutableStateFlow(doc?.lastPage ?: 0)
    val page: StateFlow<Int> = _page.asStateFlow()

    private val _content = MutableStateFlow<PageContent>(PageContent.Loading)
    val content: StateFlow<PageContent> = _content.asStateFlow()

    private val _original = MutableStateFlow<Bitmap?>(null)
    val original: StateFlow<Bitmap?> = _original.asStateFlow()

    private val _bestDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val bestDownload: StateFlow<DownloadState> = _bestDownload.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _highlight = MutableStateFlow<String?>(null)
    val highlight: StateFlow<String?> = _highlight.asStateFlow()

    private var current = ReaderSettings()
    private var variant = current.ocrVariant
    private var settingsLoaded = false

    private val renderMutex = Mutex()   // PdfRenderer allows only one open page at a time
    private var ocrJob: Job? = null
    private var prefetchJob: Job? = null
    private var searchJob: Job? = null
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                current = s
                // effectiveVariant + bestAvailable touch the filesystem — keep off the main thread.
                val newVariant = withContext(Dispatchers.Default) { effectiveVariant(s) }
                val first = !settingsLoaded
                settingsLoaded = true
                if (first) {
                    variant = newVariant
                    loadPage(_page.value)
                } else if (newVariant != variant) {
                    variant = newVariant
                    loadPage(_page.value)   // re-OCR with the new options
                }
                val needBest = s.quality == OcrQuality.BEST &&
                    withContext(Dispatchers.Default) { !tess.bestAvailable() }
                if (needBest) downloadBest()
            }
        }
    }

    private fun effectiveVariant(s: ReaderSettings): String {
        val q = if (s.quality == OcrQuality.BEST && !tess.bestAvailable()) OcrQuality.FAST else s.quality
        return "${q.name.lowercase()}_${if (s.enhance) "e1" else "e0"}"
    }

    // --- navigation ---

    fun goTo(index: Int) {
        val target = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val settled = _content.value !is PageContent.Loading && _content.value !is PageContent.Error
        if (target == _page.value && settled) return   // allow retry when Error/Loading
        _page.value = target
        doc?.let { library.saveLastPage(it.id, target, System.currentTimeMillis()) }
        loadPage(target)
    }

    // Manual paging drops any active search highlight; openHit() uses goTo() directly to keep it.
    fun next() { _highlight.value = null; goTo(_page.value + 1) }
    fun prev() { _highlight.value = null; goTo(_page.value - 1) }

    // --- OCR pipeline ---

    private suspend fun renderPage(index: Int): Bitmap =
        renderMutex.withLock { withContext(Dispatchers.Default) { source!!.render(index) } }

    /**
     * Build the reflowed page (cache-first): paragraphs interleaved with figures.
     * The page is deskewed in COLOR so figure crops keep their original colors but are
     * aligned; a binarized copy is used only for OCR.
     */
    private suspend fun ocrLayout(index: Int): List<PageElement> {
        val s = current
        val v = effectiveVariant(s)
        library.cachedLayout(docId, index, v)?.let { return it.toElements() }

        val rendered = renderPage(index)
        val angle = if (s.enhance) {
            withContext(Dispatchers.Default) { ImagePrep.estimateSkewDegrees(rendered) }
        } else 0f
        val aligned =
            if (abs(angle) >= ImagePrep.MIN_SKEW) {
                withContext(Dispatchers.Default) { ImagePrep.rotate(rendered, angle).also { rendered.recycle() } }
            } else rendered
        val ocrBmp = if (s.enhance) withContext(Dispatchers.Default) { ImagePrep.binarize(aligned) } else aligned

        // Split into text columns ourselves and OCR each left-to-right, so reading order
        // is guaranteed even when columns' lines align (where Tesseract reads across).
        // A full-width header (spanning title) is OCR'd whole above the columns.
        val layout = withContext(Dispatchers.Default) { ColumnSplitter.analyze(ocrBmp) }
        val paras = if (layout.columns.size <= 1) {
            tess.recognizeParas(ocrBmp, s.quality)
        } else {
            val acc = ArrayList<TextPara>()
            val top = layout.headerBottomPx.coerceIn(0, ocrBmp.height - 1)
            if (top > 8) {
                val header = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(ocrBmp, 0, 0, ocrBmp.width, top)
                }
                acc += tess.recognizeParas(header, s.quality)   // header boxes are already page coords
                header.recycle()
            }
            val colH = ocrBmp.height - top
            for (band in layout.columns) {
                val sub = withContext(Dispatchers.Default) {
                    Bitmap.createBitmap(ocrBmp, band.first, top, band.last - band.first + 1, colH)
                }
                tess.recognizeParas(sub, s.quality).forEach {
                    acc += it.copy(box = Rect(it.box.left + band.first, it.box.top + top, it.box.right + band.first, it.box.bottom + top))
                }
                sub.recycle()
            }
            acc
        }
        if (ocrBmp !== aligned) ocrBmp.recycle()
        // Decide headings against PAGE-wide medians (consistent across columns). A heading
        // is either clearly taller than body text, OR a short standalone line (not a
        // full-width body paragraph) that is at least slightly taller than body — OCR
        // line-height measurement is noisy, so the "short line" signal adds robustness.
        // Body = multi-line paragraphs (≥ ~2 lines); their medians define "normal" text,
        // unpolluted by single-line headings/captions that would otherwise skew it.
        val body = paras.filter { it.lineHeightPx > 0 && it.box.height() >= it.lineHeightPx * 1.8f }
        val pool = body.ifEmpty { paras.filter { it.lineHeightPx > 0 } }
        val heights = pool.map { it.lineHeightPx }.sorted()
        val median = if (heights.isEmpty()) 0 else heights[heights.size / 2]
        val widths = pool.map { it.box.width() }.sorted()
        val medianW = if (widths.isEmpty()) 0 else widths[widths.size / 2]
        fun isHeading(p: TextPara): Boolean {
            if (median <= 0 || p.lineHeightPx <= 0) return false
            val tall = p.lineHeightPx >= median * 1.3f
            val shortLine = medianW > 0 && p.box.width() < medianW * 0.6f
            val singleLine = p.box.height() < median * 2f
            return tall || (shortLine && singleLine && p.lineHeightPx >= median * 1.05f)
        }
        val resolved = paras.mapNotNull { p ->
            val collapsed = Reflow.collapse(p.text)
            if (collapsed.isEmpty()) return@mapNotNull null
            val heading = isHeading(p)
            // Headings stay headings; only non-heading lines are tested for formula-ness.
            ResolvedPara(p.box, collapsed, !heading && MathText.isFormulaLike(collapsed), heading)
        }

        val stored = withContext(Dispatchers.Default) {
            val figures = FigureFinder.find(aligned, resolved.map { it.box })
            buildStored(index, v, resolved, figures, aligned)
        }
        aligned.recycle()
        library.saveLayout(docId, index, v, stored)
        return stored.toElements()
    }

    /** A paragraph after collapsing line wraps, flagged as formula and/or heading. */
    private data class ResolvedPara(
        val box: Rect, val text: String, val formula: Boolean, val heading: Boolean = false,
    )

    /** Crop figures from the aligned color page and interleave them with paragraphs by reading order. */
    private fun buildStored(
        index: Int, variant: String, paras: List<ResolvedPara>, figures: List<Rect>, aligned: Bitmap,
    ): StoredPage {
        val keyed = ArrayList<Pair<Double, StoredElement>>()
        paras.forEachIndexed { i, p ->
            keyed += i.toDouble() to StoredElement(text = p.text, formula = p.formula, heading = p.heading)
        }
        figures.forEachIndexed { idx, box ->
            val safe = Rect(
                box.left.coerceIn(0, aligned.width - 1),
                box.top.coerceIn(0, aligned.height - 1),
                box.right.coerceIn(1, aligned.width),
                box.bottom.coerceIn(1, aligned.height),
            )
            if (safe.width() < 8 || safe.height() < 8) return@forEachIndexed
            val crop = Bitmap.createBitmap(aligned, safe.left, safe.top, safe.width(), safe.height())
            val file = library.figureFile(docId, index, variant, idx)
            runCatching {
                FileOutputStream(file).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            crop.recycle()
            // Place the figure after every paragraph whose vertical center sits above it.
            val above = paras.count { it.box.centerY() < safe.centerY() }
            keyed += (above - 0.5) to StoredElement(image = file.absolutePath, w = safe.width(), h = safe.height())
        }
        keyed.sortBy { it.first }
        return StoredPage(keyed.map { it.second })
    }

    private fun StoredPage.toElements(): List<PageElement> = elements.mapNotNull { e ->
        when {
            e.image != null -> PageElement.Figure(e.image, e.w, e.h)
            e.text != null && e.heading -> PageElement.Heading(e.text)
            e.text != null && e.formula -> PageElement.Formula(e.text)
            e.text != null -> PageElement.Paragraph(e.text)
            else -> null
        }
    }

    /** Plain text of a page (headings + paragraphs + formulas), for search. */
    private suspend fun pageText(index: Int): String =
        ocrLayout(index).mapNotNull {
            when (it) {
                is PageElement.Paragraph -> it.text
                is PageElement.Heading -> it.text
                is PageElement.Formula -> it.text
                else -> null
            }
        }.joinToString("\n\n")

    private fun loadPage(index: Int) {
        if (source == null) {
            _content.value = PageContent.Error("Cannot open this PDF.")
            return
        }
        _original.value?.recycle()      // release the previous page's "view original" bitmap
        _original.value = null
        ocrJob?.cancel()
        prefetchJob?.cancel()
        _content.value = PageContent.Loading
        ocrJob = viewModelScope.launch(Dispatchers.Default) {
            val elements = try {
                ocrLayout(index)
            } catch (c: CancellationException) {
                throw c                  // let a superseded page flip cancel cleanly
            } catch (e: Exception) {
                _content.value = PageContent.Error(e.message ?: "OCR failed")
                return@launch
            }
            // A faster page flip may have superseded us while OCR was running.
            if (!isActive || index != _page.value) return@launch
            _content.value =
                if (elements.isEmpty()) PageContent.Empty("No text recognized on this page.")
                else PageContent.Ready(elements)
            prefetchNeighbors(index)
        }
    }

    /** OCR the adjacent pages in the background so flipping feels instant. */
    private fun prefetchNeighbors(index: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.Default) {
            for (p in listOf(index + 1, index - 1)) {
                if (p !in 0 until pageCount) continue
                if (library.cachedLayout(docId, p, effectiveVariant(current)) != null) continue
                runCatching { ocrLayout(p) }
            }
        }
    }

    /** Render the current page image for the "view original" overlay. */
    fun loadOriginal() {
        if (source == null || _original.value != null) return
        val index = _page.value
        viewModelScope.launch {
            val bmp = runCatching {
                renderMutex.withLock {
                    withContext(Dispatchers.Default) {
                        source.render(index, targetDpi = 200).downscaled(2200)
                    }
                }
            }.getOrNull()
            _original.value = bmp
        }
    }

    // --- best models ---

    private fun downloadBest() {
        if (_bestDownload.value is DownloadState.Running) return
        downloadJob = viewModelScope.launch {
            _bestDownload.value = DownloadState.Running(0f)
            val ok = tess.downloadBest { f -> _bestDownload.value = DownloadState.Running(f) }
            _bestDownload.value = if (ok) DownloadState.Done else DownloadState.Failed
            if (ok && current.quality == OcrQuality.BEST) {
                variant = effectiveVariant(current)
                loadPage(_page.value)   // re-OCR current page with best now available
            }
        }
    }

    // --- full-text search ---

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) { clearSearch(); return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.Default) {
            val hits = mutableListOf<SearchHit>()
            for (p in 0 until pageCount) {
                ensureActive()           // stop OCR-ing pages if the search was cancelled
                _searchState.value = SearchState.Indexing(p, pageCount)
                val text = try {
                    pageText(p)
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    ""
                }
                val idx = text.indexOf(q, ignoreCase = true)
                if (idx >= 0) hits += SearchHit(p, buildSnippet(text, idx, q.length))
            }
            _searchState.value = SearchState.Results(q, hits)
        }
    }

    fun openHit(hit: SearchHit, query: String) {
        _highlight.value = query
        goTo(hit.page)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchState.value = SearchState.Idle
        _highlight.value = null
    }

    override fun onCleared() {
        ocrJob?.cancel(); prefetchJob?.cancel(); searchJob?.cancel(); downloadJob?.cancel()
        _original.value?.recycle()
        source?.close()
    }

    class Factory(private val app: PdferApp, private val docId: String) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(app.library, app.tess, app.settings, docId) as T
    }
}
