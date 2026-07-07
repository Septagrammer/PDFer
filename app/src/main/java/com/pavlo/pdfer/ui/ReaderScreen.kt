package com.pavlo.pdfer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pavlo.pdfer.PdferApp
import com.pavlo.pdfer.data.MathText
import com.pavlo.pdfer.data.OcrQuality
import com.pavlo.pdfer.data.PageElement
import com.pavlo.pdfer.data.Script
import com.pavlo.pdfer.data.ReaderSettings
import com.pavlo.pdfer.data.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReaderScreen(app: PdferApp, docId: String, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory(app, docId))
    val scope = rememberCoroutineScope()

    val page by vm.page.collectAsState()
    val content by vm.content.collectAsState()
    val settings by vm.settings.collectAsState(initial = ReaderSettings())
    val original by vm.original.collectAsState()
    val bestDownload by vm.bestDownload.collectAsState()
    val searchState by vm.searchState.collectAsState()
    val highlight by vm.highlight.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val palette = settings.theme.palette()

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // Top bar (normal vs search mode)
        if (showSearch) {
            SearchBar(
                query = query,
                palette = palette,
                onQuery = { query = it },
                onSubmit = { vm.search(query) },
                onClose = { showSearch = false; query = ""; vm.clearSearch() },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = palette.text)
                }
                Text(
                    vm.doc?.title ?: "Reader",
                    color = palette.text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showSearch = true }) {
                    Icon(Icons.Default.Search, "Search", tint = palette.text)
                }
                IconButton(onClick = { showOriginal = true; vm.loadOriginal() }) {
                    Icon(Icons.Default.Image, "View original", tint = palette.text)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.TextFields, "Text settings", tint = palette.text)
                }
            }
        }

        // Body: search results take over when searching, otherwise the page text
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val ss = searchState
            if (showSearch && ss !is SearchState.Idle) {
                SearchResults(ss, palette) { hit, q ->
                    vm.openHit(hit, q); showSearch = false
                }
            } else {
                PageBody(content, settings, palette, highlight)
            }
        }

        // Bottom navigation
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.prev() }, enabled = page > 0) {
                Icon(Icons.Default.ChevronLeft, "Previous", tint = palette.text)
            }
            if (vm.pageCount > 1) {
                // Track the thumb locally while dragging; only commit (save + OCR) on release.
                var seeking by remember { mutableStateOf(false) }
                var seekPos by remember { mutableStateOf(page.toFloat()) }
                Slider(
                    value = if (seeking) seekPos else page.toFloat(),
                    onValueChange = { seeking = true; seekPos = it },
                    onValueChangeFinished = { seeking = false; vm.goTo(seekPos.toInt()) },
                    valueRange = 0f..(vm.pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                "${page + 1}/${vm.pageCount}",
                color = palette.faint,
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { vm.next() }, enabled = page < vm.pageCount - 1) {
                Icon(Icons.Default.ChevronRight, "Next", tint = palette.text)
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            bestDownload = bestDownload,
            onChange = { scope.launch { app.settings.update(it) } },
            onDismiss = { showSettings = false },
        )
    }

    if (showOriginal) {
        OriginalOverlay(bitmap = original, onClose = { showOriginal = false })
    }
}

@Composable
private fun PageBody(
    content: PageContent,
    settings: ReaderSettings,
    palette: ReaderPalette,
    highlight: String?,
) {
    when (content) {
        is PageContent.Loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = palette.text)
            Text("Recognizing text…", color = palette.faint, modifier = Modifier.padding(top = 12.dp))
        }
        is PageContent.Error -> CenteredNote(content.message, palette.faint)
        is PageContent.Empty -> CenteredNote(content.message, palette.faint)
        is PageContent.Ready -> {
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy((settings.fontSizeSp * 0.6f).dp),
            ) {
                for (el in content.elements) {
                    when (el) {
                        is PageElement.Heading -> Text(
                            text = if (highlight == null) mathStyled(el.text, (settings.fontSizeSp * 1.5f).toInt())
                            else highlighted(el.text, highlight),
                            color = palette.text,
                            fontSize = (settings.fontSizeSp * 1.5f).sp,
                            lineHeight = (settings.fontSizeSp * 1.7f).sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        )
                        is PageElement.Paragraph -> Text(
                            // Active search highlight takes precedence; otherwise raise powers.
                            text = if (highlight == null) mathStyled(el.text, settings.fontSizeSp)
                            else highlighted(el.text, highlight),
                            color = palette.text,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = settings.fontSizeSp.sp * settings.lineHeightMult,
                            fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                            textAlign = if (settings.justify) TextAlign.Justify else TextAlign.Start,
                        )
                        is PageElement.Formula -> Text(
                            text = mathStyled(el.text, settings.fontSizeSp, primeAsSuper = true),
                            color = palette.text,
                            fontSize = settings.fontSizeSp.sp,
                            lineHeight = settings.fontSizeSp.sp * settings.lineHeightMult,
                            fontFamily = FontFamily.Monospace,   // formulas kept as text, monospaced
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        is PageElement.Figure -> FigureImage(el)
                    }
                }
            }
        }
    }
}

/** Render text with `^`/`_` as real super/subscripts (formulas and inline powers in prose). */
private fun mathStyled(text: String, baseSp: Int, primeAsSuper: Boolean = false): AnnotatedString {
    val small = (baseSp * 0.72f).sp
    val pretty = MathText.withMultiplicationDots(text)   // "a * b" -> "a·b"
    return buildAnnotatedString {
        for (seg in MathText.toSegments(pretty, primeAsSuper)) when (seg.script) {
            Script.NORMAL -> append(seg.text)
            Script.SUPER -> withStyle(
                SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = small)
            ) { append(seg.text) }
            Script.SUB -> withStyle(
                SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = small)
            ) { append(seg.text) }
        }
    }
}

private fun highlighted(text: String, query: String?): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val low = text.lowercase(); val q = query.lowercase()
    if (!low.contains(q)) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        while (true) {
            val f = low.indexOf(q, i)
            if (f < 0) { append(text.substring(i)); break }
            append(text.substring(i, f))
            withStyle(SpanStyle(background = Color(0xFFFFE082), color = Color(0xFF1A1A1A))) {
                append(text.substring(f, f + q.length))
            }
            i = f + q.length
        }
    }
}

@Composable
private fun FigureImage(fig: PageElement.Figure) {
    // Decode the cropped original-color figure off the main thread, cached per path.
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, fig.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(fig.path) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Figure",
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentScale = ContentScale.FillWidth,
        )
    }
}

@Composable
private fun CenteredNote(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, modifier = Modifier.padding(32.dp), textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    palette: ReaderPalette,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search", tint = palette.text)
        }
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search in document") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = palette.text,
                unfocusedTextColor = palette.text,
            ),
        )
        IconButton(onClick = onSubmit) {
            Icon(Icons.Default.Search, "Run search", tint = palette.text)
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchState,
    palette: ReaderPalette,
    onHit: (SearchHit, String) -> Unit,
) {
    when (state) {
        is SearchState.Indexing -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                progress = { (state.done + 1f) / state.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Reading pages… ${state.done + 1}/${state.total}",
                color = palette.faint,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        is SearchState.Results -> {
            if (state.hits.isEmpty()) {
                CenteredNote("No matches for “${state.query}”.", palette.faint)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.hits) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onHit(hit, state.query) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Text(
                                "Page ${hit.page + 1}",
                                color = palette.text,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                highlighted(hit.snippet, state.query),
                                color = palette.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HorizontalDivider(color = palette.faint.copy(alpha = 0.2f))
                    }
                }
            }
        }
        SearchState.Idle -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    settings: ReaderSettings,
    bestDownload: DownloadState,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Text size: ${settings.fontSizeSp}sp", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { onChange(settings.copy(fontSizeSp = it.toInt())) },
                valueRange = 13f..34f,
            )
            Text("Line spacing: ${"%.1f".format(settings.lineHeightMult)}×", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = settings.lineHeightMult,
                onValueChange = { onChange(settings.copy(lineHeightMult = (it * 10).toInt() / 10f)) },
                valueRange = 1.1f..2.2f,
            )
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderTheme.entries.forEach { t ->
                    val pal = t.palette()
                    val selected = t == settings.theme
                    Box(
                        Modifier
                            .size(width = 72.dp, height = 48.dp)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else pal.faint,
                                shape = MaterialTheme.shapes.small,
                            )
                            .background(pal.background, MaterialTheme.shapes.small)
                            .clickable { onChange(settings.copy(theme = t)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = pal.text,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Serif font", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = settings.serif,
                    onCheckedChange = { onChange(settings.copy(serif = it)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Justify text", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = settings.justify,
                    onCheckedChange = { onChange(settings.copy(justify = it)) },
                )
            }

            HorizontalDivider()

            Text("Recognition accuracy", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = settings.quality == OcrQuality.FAST,
                    onClick = { onChange(settings.copy(quality = OcrQuality.FAST)) },
                    label = { Text("Fast") },
                )
                FilterChip(
                    selected = settings.quality == OcrQuality.BEST,
                    onClick = { onChange(settings.copy(quality = OcrQuality.BEST)) },
                    label = { Text("Best") },
                )
            }
            when (val d = bestDownload) {
                is DownloadState.Running -> Column {
                    Text("Downloading best models… ${(d.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                DownloadState.Failed -> Text(
                    "Download failed — using Fast. Check connection and reselect Best.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> if (settings.quality == OcrQuality.BEST) Text(
                    "Best: slower but more accurate on poor scans.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enhance scan", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Deskew + binarize before OCR",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enhance,
                    onCheckedChange = { onChange(settings.copy(enhance = it)) },
                )
            }
        }
    }
}

@Composable
private fun OriginalOverlay(bitmap: android.graphics.Bitmap?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                var scale by remember { mutableStateOf(1f) }
                var offX by remember { mutableStateOf(0f) }
                var offY by remember { mutableStateOf(0f) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Original page",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offX += pan.x
                                offY += pan.y
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offX, translationY = offY,
                        ),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
        }
    }
}
