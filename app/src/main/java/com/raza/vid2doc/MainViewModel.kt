package com.raza.vid2doc

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UiState {
    data object Idle : UiState
    data class Working(val stage: String, val fraction: Float?) : UiState
    data class Done(val uri: Uri, val pages: Int) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var url by mutableStateOf("")
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    // One-shot "open this PDF" events, delivered when the app is in the foreground.
    private val _open = Channel<Uri>(Channel.BUFFERED)
    val openEvents = _open.receiveAsFlow()

    private var job: Job? = null
    private var processId = ""

    fun start() {
        if (job?.isActive == true) return
        val link = url.trim()
        if (link.isEmpty()) return
        processId = UUID.randomUUID().toString()
        val pid = processId
        state = UiState.Working("Starting…", null)
        job = viewModelScope.launch {
            try {
                val r = Converter.convert(getApplication(), link, pid) {
                    state = UiState.Working(it.stage, it.fraction)
                }
                state = UiState.Done(r.uri, r.pages)
                _open.send(r.uri)
            } catch (e: CancellationException) {
                state = UiState.Idle
                throw e
            } catch (e: Throwable) {
                state = UiState.Error(friendly(e))
            }
        }
    }

    fun cancel() {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        job?.cancel()
        state = UiState.Idle
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Unable to resolve host", true) || m.contains("network", true) ->
                "No internet connection."
            m.contains("Unsupported URL", true) || m.contains("is not a valid URL", true) ->
                "That doesn't look like a valid video link."
            m.contains("Private video", true) || m.contains("Sign in", true) ->
                "This video is private or needs a login."
            else -> "Something went wrong: ${m.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: e.javaClass.simpleName}"
        }
    }
}
