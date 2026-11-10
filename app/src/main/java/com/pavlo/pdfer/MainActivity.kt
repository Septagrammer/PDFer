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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pavlo.pdfer.ui.AppTheme
import com.pavlo.pdfer.ui.LibraryScreen
import kotlinx.coroutines.CancellationException
import com.pavlo.pdfer.ui.ReaderScreen

class MainActivity : ComponentActivity() {

    // PDF to import from a VIEW intent. Compose state so onNewIntent updates are observed.
    private class ImportRequest(val uri: Uri)
    private var pendingImport by mutableStateOf<ImportRequest?>(null)
    private var intentImportError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PdferApp
        pendingImport = viewUriOf(intent)?.let(::ImportRequest)

        setContent {
            AppTheme {
                val nav = rememberNavController()

                // A PDF opened from another app: import it, then jump to the reader.
                LaunchedEffect(pendingImport) {
                    val request = pendingImport ?: return@LaunchedEffect
                    intentImportError = null
                    try {
                        val doc = app.library.import(request.uri, System.currentTimeMillis())
                        nav.navigate("reader/${doc.id}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        intentImportError = e.message ?: "Could not open this PDF."
                    } finally {
                        if (pendingImport === request) pendingImport = null
                    }
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

                intentImportError?.let { message ->
                    AlertDialog(
                        onDismissRequest = { intentImportError = null },
                        confirmButton = {
                            TextButton(onClick = { intentImportError = null }) { Text("OK") }
                        },
                        title = { Text("Couldn’t open PDF") },
                        text = { Text(message) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewUriOf(intent)?.let {
            intentImportError = null
            pendingImport = ImportRequest(it)
        }
    }

    private fun viewUriOf(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
