package com.pavlo.pdfer

import android.app.Application
import com.pavlo.pdfer.data.LibraryRepository
import com.pavlo.pdfer.data.SettingsStore
import com.pavlo.pdfer.data.TessManager

/** Process-wide singletons. Kept simple — no DI framework. */
class PdferApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var tess: TessManager
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        tess = TessManager(this)
        settings = SettingsStore(this)
    }
}
