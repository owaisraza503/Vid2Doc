package com.raza.vid2doc

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleIntent(intent)

        // Auto-open the finished PDF as soon as the app is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.openEvents.collect { openPdf(it) }
            }
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen(vm, onOpen = ::openPdf)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Regex("https?://\\S+").find(text)?.value?.let { vm.url = it }
        }
    }

    private fun openPdf(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(view).setPackage("com.adobe.reader"))   // Adobe Acrobat
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent.createChooser(view, "Open PDF with"))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "No PDF viewer found. File is in Downloads/Vid2Doc.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun Screen(vm: MainViewModel, onOpen: (Uri) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val state = vm.state
    val busy = state is UiState.Working

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Vid2Doc", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Paste a YouTube link, get a high-res PDF.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy,
            placeholder = { Text("https://youtube.com/watch?v=…") },
            trailingIcon = {
                TextButton(enabled = !busy, onClick = {
                    clipboard.getText()?.text?.let { vm.url = it.trim() }
                }) { Text("Paste") }
            }
        )
        Spacer(Modifier.height(16.dp))

        when (state) {
            is UiState.Working -> {
                if (state.fraction != null)
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                else
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(state.stage)
                TextButton(onClick = vm::cancel) { Text("Cancel") }
                Text(
                    "Keep the app open until it finishes.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Button(
                    onClick = vm::start,
                    enabled = vm.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Convert to PDF", fontSize = 16.sp) }
            }
        }

        Spacer(Modifier.height(20.dp))
        when (state) {
            is UiState.Done -> {
                Text("Done · ${state.pages} pages\nSaved in Downloads/Vid2Doc", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onOpen(state.uri) }) { Text("Open in Acrobat") }
            }

            is UiState.Error -> Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            else -> {}
        }
    }
}
