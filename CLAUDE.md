# PDFer

Android app for comfortably reading PDFs - including badly scanned ones. OCRs each
page (Tesseract, on-device), detects columns, and reflows the recognized text into a
clean, adjustable reading view (font/size/spacing/theme). A "view original" button
shows the raw scanned page. Personal project - no git.

## Stack
- Kotlin + Jetpack Compose, single `:app` module, MVVM, no DI framework.
- minSdk 26, compileSdk/targetSdk 35.
- OCR: `cz.adaptech.tesseract4android:tesseract4android:4.9.0` (via **JitPack**, not
  Maven Central - the `maven { url "https://jitpack.io" }` repo in settings.gradle.kts
  is required).
- PDF rendering: Android `PdfRenderer` (needs a seekable fd → docs are copied into
  app storage on import).
- Languages: `eng + rus + ukr + ces`.
  - FAST (default): tessdata_fast bundled in `app/src/main/assets/tessdata/`.
  - BEST: tessdata_best (~52MB) downloaded on demand into `filesDir/best/tessdata/`
    when the user picks "Best" accuracy. Needs INTERNET permission.
  - Re-download from github tessdata_fast / tessdata_best if needed.

## Toolchain gotcha
AGP 8.7 does NOT work with the machine default JDK (25/26). **Build with JDK 21:**
```
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew :app:assembleDebug
```
Android SDK lives at `/opt/homebrew/share/android-commandlinetools` (Homebrew); path
is in `local.properties`.

## Run on emulator
```
/opt/homebrew/share/android-commandlinetools/emulator/emulator -avd lectus -no-snapshot &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.pavlo.pdfer/.MainActivity
```
Open a PDF from another app (or push one and fire a VIEW intent with
`--grant-read-uri-permission`).

## Architecture
- `data/TessManager` - FAST + BEST engines (one TessBaseAPI each, lazy), mutex-
  serialized, PSM_AUTO (auto column/layout detection → reading-order text). Copies
  fast traineddata out of assets; downloads best on demand. BEST falls back to FAST if
  not yet downloaded, so recognition never hard-fails.
- `data/ImagePrep` - pure-Kotlin scan enhancement (no OpenCV): grayscale, deskew
  (projection-profile angle search ±4°, `estimateSkewDegrees`/`rotate`), Otsu
  `binarize`. Big accuracy win on tilted/noisy scans.
- `data/FigureFinder` - detects figure/photo regions on the (deskewed) page so they
  can be shown as images instead of OCR'd. Pixel-based (Tesseract4Android exposes no
  block typing): "figure content" = pixels OUTSIDE recognized text boxes that are dark
  ink OR mid-gray continuous tone; grouped on a coarse cell grid; only large, densely-
  filled blocks survive. Returns bounding rects.
- `data/Layout` - `TextPara` (box+text), `PageElement` (Paragraph | Formula | Figure),
  and the serializable `StoredPage`/`StoredElement` cache form.
- `data/MathText` - `isFormulaLike(text)` classifies a recognized line as a formula by
  math-symbol/digit density and low prose-word count (rendered monospaced, kept as text,
  NOT imageified). `toSegments(text)` splits `^`/`_` into super/subscript runs so powers
  render as real raised exponents (a² not a^2); the reader's `mathStyled()` turns those
  into `BaselineShift` spans, applied to formulas always and to prose when no search
  highlight is active. In formula context (`primeAsSuper=true`) a prime/apostrophe
  directly before a digit is treated as a misread caret (scans often OCR "^" as "'"),
  so "b'2" → b² — but standalone primes (f') and prose ("'90s") stay literal. Languages: the multi-lang LSTM model handles Ukrainian
  mixed with English words in one pass. NOTE: the legacy `equ` math model was tried and
  REMOVED — it mangled clean input (e.g. "1 + 2 = 3" → "↿⊹∑∶∂"); formulas use the main
  engine, so simple ones are accurate but exotic symbols (∫∑√, sub/superscripts) degrade.

### Figure pipeline (reflow keeps pictures)
The page is rendered in COLOR, deskewed in color (`ImagePrep.rotate` by the estimated
angle) so figure crops keep their original colors but are straightened; a binarized
copy is made ONLY for OCR. `TessManager.recognizeParas` returns paragraphs with boxes
in reading order; `FigureFinder` finds image regions; each figure is cropped from the
aligned color page (saved as PNG) and interleaved among paragraphs by vertical
position. The reader renders `PageElement.Paragraph` as text and `PageElement.Figure`
as an inline `Image`. A pure-image page (no text) shows as just the picture.
### Columns (reading order)
`data/ColumnSplitter.analyze` builds a vertical ink profile over the page BODY (skips
the top ~18% so a full-width title doesn't fill the gutters), max-filter-smooths it to
bridge within-column gaps, and cuts columns at wide low-ink gutters (relative threshold
~12% of the median column ink → adapts sparse/dense). It also returns a `headerBottomPx`
band (the spanning title), OCR'd full-width above the columns. `ReaderViewModel.ocrLayout`
OCRs the header then each column band left-to-right and concatenates — guaranteeing order,
since Tesseract's PSM_AUTO reads straight across columns when their lines align. Handles
2–6 columns on clean input (instrumented). **Limitation:** on heavily degraded sparse
multi-column scans (noise + skew) the split can still be imperfect and fall back to
PSM_AUTO ordering; 2-column works well, 4–6 best-effort.

### Headings
`TessManager.recognizeParas` measures each paragraph's line height from its text-line
boxes. `ReaderViewModel` flags a paragraph as a heading vs the **body median** (computed
from multi-line paragraphs only, so single-line titles don't skew it): heading if it's
clearly taller (≥1.3×) OR a short standalone line (< 60% of body width) that is at least
slightly taller (≥1.05×). Rendered larger + bold (`PageElement.Heading`). OCR line-height
is noisy, so very small heading/body size ratios may be missed — document titles are
reliably caught.

- `data/PdfPageSource` - renders a page to a bitmap at ~300dpi (capped 3000px).
- `data/Reflow` - collapses hard line wraps within each paragraph, repairs end-of-line
  hyphenation.
- `data/LibraryRepository` - imports (copy + SHA-256 id), recents (`library.json`),
  last-page, and the per-page caches **keyed by variant** (`<quality>_<e0|e1>`):
  the page layout `cacheDir/ocr/<id>/<page>_<variant>.json` (paragraphs + figure refs)
  and figure crops `<page>_<variant>_img<n>.png`. A layout cache entry is treated as a
  miss if any referenced crop file is gone. (The older plain-text `…_<variant>.txt`
  helpers remain for tests.)
- `data/SettingsStore` - reader settings via DataStore: font size, line spacing,
  `serif`, `theme` (default **LIGHT/white**), `quality` (FAST/BEST), `enhance`, and
  `justify` (default **off** — ragged-right; toggle in the settings sheet).
  `ReaderSettings.ocrVariant` builds the cache key (justify/theme don't affect it).
  The reader renders multiplication `*` as a tight middle dot via
  `MathText.withMultiplicationDots` ("a * b" → "a·b", no surrounding spaces).
- `ui/ReaderViewModel` - observes settings (re-OCRs current page when quality/enhance
  changes), cache-first per-page OCR, **background prefetch of adjacent pages**, best-
  model download state, and **full-text search** (OCRs all pages on first query with
  progress, returns page+snippet hits, drives match highlighting).
- `ui/ReaderScreen` - reflow display with match highlighting, search bar + results,
  settings sheet (scrollable), zoomable original overlay. Render calls are serialized
  through a Mutex in the VM (PdfRenderer allows only one open page at a time).

## Verified on emulator (2026-06-28)
Enhance gives a large accuracy jump on a deliberately tilted+noisy two-column test
scan. Best is cleaner still. Column reading order, RU/UA/EN OCR, search+highlight,
prefetch, variant cache, live theme all working.

## Tests
JDK 21 for all gradle commands (see toolchain gotcha above).
- Unit (JVM, fast): `./gradlew :app:testDebugUnitTest` — 51 tests. Pure logic:
  `Reflow.paragraphs`/`collapse`, `ReaderSettings.ocrVariant`, `buildSnippet`
  (`data/SearchText.kt`), `StoredPage` serialization, `MathText.isFormulaLike` +
  `toSegments` (super/subscript splitting).
- Instrumented (needs a running emulator/device): `./gradlew :app:connectedDebugAndroidTest`
  — 40 tests, incl. `ColumnAndHeadingTest` (2/3/4/6-column split + left→right order,
  single column not split, larger title detected as heading), `PdfPageSource`, `ImagePrep`
  (deskew angle sign, binarization),
  `FigureFinder` (detects a continuous-tone block, ignores sparse text), `TessManager`
  (real FAST OCR, BEST→FAST fallback, Ukrainian+English mixed, simple formula as text),
  `LibraryRepository` (import, text + layout cache, variant isolation, missing-crop
  invalidation, delete), `SettingsStore` round-trip, and a `LibraryScreen` Compose smoke
  test. Run a single class with
  `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>` (`--tests` is NOT supported
  for connected tests). The FAB text sits under a ClearAndSetSemantics node → match it
  on the unmerged tree in Compose tests.

## Review/fix pass (2026-06-28)
A 4-agent review + test-writing pass hardened the VM/UI: superseded page-flips no
longer paint stale text (guard `isActive`/`index`, don't swallow `CancellationException`);
OCR/search/prefetch run on `Dispatchers.Default` (cache reads were on the main thread);
the "view original" bitmap is recycled; import failures (picker + VIEW intent) no longer
crash or hang; `onNewIntent` handles a second "open PDF"; Error pages can be retried;
search highlight clears on manual paging; the page slider commits on release only;
deskew score is normalized; best-model download cleans up `.part` files.

## Next ideas (not done)
- Adaptive (Sauvola) binarization for unevenly-lit scans; despeckle.
- Persist a search index so re-search across sessions is instant.
- Reading progress sync / bookmarks; share/export recognized text.
