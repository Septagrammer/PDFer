package com.pavlo.pdfer.data

import kotlinx.serialization.Serializable

/** A PDF that has been imported into the app's storage. */
@Serializable
data class DocumentRef(
    val id: String,            // stable hash of the original content
    val title: String,         // display name
    val fileName: String,      // file inside filesDir/docs
    val pageCount: Int,
    val addedAt: Long,
    val lastPage: Int = 0,
    val lastOpenedAt: Long = 0L,
)

/** OCR languages bundled with the app (tessdata_fast). */
object Langs {
    const val ALL = "eng+rus+ukr+ces"
}
