package com.pavlo.pdfer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderTheme { LIGHT, SEPIA, DARK }

/** Fast = bundled tessdata_fast; Best = downloadable tessdata_best (slower, more accurate). */
enum class OcrQuality { FAST, BEST }

data class ReaderSettings(
    val fontSizeSp: Int = 19,
    val lineHeightMult: Float = 1.5f,
    val serif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.LIGHT,   // white background by default
    val quality: OcrQuality = OcrQuality.FAST,
    val enhance: Boolean = true,
    val justify: Boolean = false,                 // ragged-right by default
) {
    /** Cache/OCR variant key — text recognized with different options is cached separately. */
    val ocrVariant: String get() = "${quality.name.lowercase()}_${if (enhance) "e1" else "e0"}"
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("reader_settings")

class SettingsStore(private val app: Context) {
    private object Keys {
        val size = intPreferencesKey("font_size")
        val line = floatPreferencesKey("line_height")
        val serif = booleanPreferencesKey("serif")
        val theme = stringPreferencesKey("theme")
        val quality = stringPreferencesKey("ocr_quality")
        val enhance = booleanPreferencesKey("enhance")
        val justify = booleanPreferencesKey("justify")
    }

    val settings: Flow<ReaderSettings> = app.dataStore.data.map { p ->
        ReaderSettings(
            fontSizeSp = p[Keys.size] ?: 19,
            lineHeightMult = p[Keys.line] ?: 1.5f,
            serif = p[Keys.serif] ?: true,
            theme = runCatching { ReaderTheme.valueOf(p[Keys.theme] ?: "LIGHT") }
                .getOrDefault(ReaderTheme.LIGHT),
            quality = runCatching { OcrQuality.valueOf(p[Keys.quality] ?: "FAST") }
                .getOrDefault(OcrQuality.FAST),
            enhance = p[Keys.enhance] ?: true,
            justify = p[Keys.justify] ?: false,
        )
    }

    suspend fun update(s: ReaderSettings) {
        app.dataStore.edit { p ->
            p[Keys.size] = s.fontSizeSp
            p[Keys.line] = s.lineHeightMult
            p[Keys.serif] = s.serif
            p[Keys.theme] = s.theme.name
            p[Keys.quality] = s.quality.name
            p[Keys.enhance] = s.enhance
            p[Keys.justify] = s.justify
        }
    }
}
