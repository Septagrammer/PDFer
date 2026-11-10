package com.pavlo.pdfer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the Tesseract engines. The native API is not thread-safe, so all
 * recognition is serialized through a mutex and runs on the Default dispatcher.
 *
 * Two data sets are supported:
 *  - FAST: tessdata_fast, bundled in assets, copied out on first launch.
 *  - BEST: tessdata_best, downloaded on demand into filesDir/best/tessdata.
 */
class TessManager(private val appContext: Context) {

    private val fastDir: File = appContext.filesDir                 // contains tessdata/
    private val bestDir: File = File(appContext.filesDir, "best")   // contains tessdata/
    private val langs = Langs.ALL.split("+")

    private val mutex = Mutex()
    private val provisioningMutex = Mutex()
    private val engines = HashMap<OcrQuality, TessBaseAPI>()

    // --- trained data provisioning ---

    private fun ensureFastData() {
        val tessdata = File(fastDir, "tessdata").apply { mkdirs() }
        val assets = appContext.assets
        for (name in assets.list("tessdata").orEmpty()) {
            val out = File(tessdata, name)
            if (out.exists() && out.length() > 0) continue
            val tmp = File(tessdata, "$name.part")
            try {
                assets.open("tessdata/$name").use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (tmp.length() == 0L || !tmp.renameTo(out)) {
                    throw IOException("Could not provision OCR model $name")
                }
            } finally {
                tmp.delete()
            }
        }
    }

    fun bestAvailable(): Boolean {
        val tessdata = File(bestDir, "tessdata")
        return langs.all { File(tessdata, "$it.traineddata").let { f -> f.exists() && f.length() > 0 } }
    }

    /** Download the tessdata_best models. [onProgress] gets a 0..1 fraction. */
    suspend fun downloadBest(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        provisioningMutex.withLock {
            val tessdata = File(bestDir, "tessdata").apply { mkdirs() }
            val base = "https://github.com/tesseract-ocr/tessdata_best/raw/main"
            try {
                langs.forEachIndexed { i, lang ->
                    val out = File(tessdata, "$lang.traineddata")
                    if (out.exists() && out.length() > 0) {
                        onProgress((i + 1f) / langs.size); return@forEachIndexed
                    }
                    val tmp = File(tessdata, "$lang.part")
                    val connection = URL("$base/$lang.traineddata").openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 30_000
                        if (connection.responseCode !in 200..299) {
                            throw IOException("OCR model download failed: HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.use { input ->
                            FileOutputStream(tmp).use { output ->
                                input.copyTo(output, 64 * 1024)
                                output.fd.sync()
                            }
                        }
                        // A model becomes visible only after its complete temporary file
                        // has been synced and atomically switched into place.
                        if (tmp.length() == 0L || !tmp.renameTo(out)) {
                            throw IOException("Could not install OCR model $lang")
                        }
                    } finally {
                        connection.disconnect()
                        tmp.delete()
                    }
                    onProgress((i + 1f) / langs.size)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun engine(quality: OcrQuality): TessBaseAPI {
        engines[quality]?.let { return it }
        val dir = when (quality) {
            OcrQuality.FAST -> { ensureFastData(); fastDir }
            OcrQuality.BEST -> bestDir
        }
        val t = TessBaseAPI()
        check(t.init(dir.absolutePath, Langs.ALL)) {
            "Tesseract init failed for $quality (missing traineddata?)"
        }
        // PSM_AUTO: automatic page segmentation — detects columns/blocks and
        // emits text in reading order.
        t.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        engines[quality] = t
        return t
    }

    /**
     * Recognize a full page bitmap. Falls back to FAST if BEST is requested but
     * not yet downloaded, so recognition never hard-fails on a missing model.
     */
    suspend fun recognize(bitmap: Bitmap, quality: OcrQuality): String =
        withContext(Dispatchers.Default) {
            val effective = if (quality == OcrQuality.BEST && !bestAvailable()) OcrQuality.FAST else quality
            mutex.withLock {
                val t = engine(effective)
                try {
                    t.setImage(bitmap)
                    t.getUTF8Text().orEmpty()
                } finally {
                    t.clear()
                }
            }
        }

    /**
     * Recognize and return paragraphs with their bounding boxes, in reading order
     * (Tesseract orders across columns). Boxes are in [bitmap]'s pixel space.
     */
    suspend fun recognizeParas(bitmap: Bitmap, quality: OcrQuality): List<TextPara> =
        withContext(Dispatchers.Default) {
            val effective = if (quality == OcrQuality.BEST && !bestAvailable()) OcrQuality.FAST else quality
            mutex.withLock {
                val t = engine(effective)
                try {
                    t.setImage(bitmap)
                    t.getUTF8Text()   // force recognition before iterating

                    // Pass 1: text-line boxes → page median line height (body text size).
                    val lineBoxes = ArrayList<Rect>()
                    val it1 = t.resultIterator
                    it1.begin()
                    val line = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                    do {
                        val b = it1.getBoundingRect(line)
                        if (b != null && b.height() > 0 && b.width() > 0) lineBoxes.add(b)
                    } while (it1.next(line))
                    val median = medianHeight(lineBoxes)

                    // Pass 2: paragraphs in reading order; a paragraph whose lines are
                    // notably taller than the body median is flagged as a heading.
                    val out = ArrayList<TextPara>()
                    val it2 = t.resultIterator
                    it2.begin()
                    val para = TessBaseAPI.PageIteratorLevel.RIL_PARA
                    do {
                        val text = (it2.getUTF8Text(para) ?: "").trim()
                        if (text.isNotEmpty()) {
                            val box = it2.getBoundingRect(para)
                            if (box != null && box.width() > 0 && box.height() > 0) {
                                val lh = paraLineHeight(box, lineBoxes)
                                val heading = median > 0f && lineBoxes.size >= 3 && lh >= median * 1.25f
                                out.add(TextPara(box, text, heading, lh.toInt()))
                            }
                        }
                    } while (it2.next(para))
                    out
                } finally {
                    t.clear()
                }
            }
        }

    private fun medianHeight(boxes: List<Rect>): Float {
        if (boxes.isEmpty()) return 0f
        val hs = boxes.map { it.height() }.sorted()
        return hs[hs.size / 2].toFloat()
    }

    /**
     * Median height of the text lines inside [para] (its glyph size), or 0 if none are
     * found — never the paragraph box height, which includes padding and would falsely
     * look like a heading.
     */
    private fun paraLineHeight(para: Rect, lines: List<Rect>): Float {
        val inside = lines.filter { para.contains(it.centerX(), it.centerY()) }
        if (inside.isEmpty()) return 0f
        val hs = inside.map { it.height() }.sorted()
        return hs[hs.size / 2].toFloat()
    }

    fun shutdown() {
        engines.values.forEach { it.recycle() }
        engines.clear()
    }
}
