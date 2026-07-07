package com.pavlo.pdfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pavlo.pdfer.ui.AppTheme
import com.pavlo.pdfer.ui.LibraryScreen
import com.pavlo.pdfer.ui.ReaderScreen

class MainActivity : ComponentActivity() {

    // PDF to import from a VIEW intent. Compose state so onNewIntent updates are observed.
    private var pendingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PdferApp
        pendingUri = viewUriOf(intent)

        setContent {
            AppTheme {
                val nav = rememberNavController()

                // A PDF opened from another app: import it (best-effort), then jump to the reader.
                LaunchedEffect(pendingUri) {
                    val uri = pendingUri ?: return@LaunchedEffect
                    pendingUri = null
                    val doc = runCatching { app.library.import(uri, System.currentTimeMillis()) }
                        .getOrNull()
                    if (doc != null) nav.navigate("reader/${doc.id}")
                }

                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            app = app,
                            onOpen = { id -> nav.navigate("reader/$id") },
                        )
                    }
                    composable("reader/{id}") { entry ->
                        ReaderScreen(
                            app = app,
                            docId = entry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewUriOf(intent)?.let { pendingUri = it }
    }

    private fun viewUriOf(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
