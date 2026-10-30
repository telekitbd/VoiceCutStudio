package com.voicecut.studio

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface JobState {
    data object Idle : JobState
    data class Running(val message: String, val progress: Float? = null) : JobState
    data class Done(val message: String, val uri: Uri? = null, val mime: String? = null) : JobState
    data class Failed(val message: String) : JobState
}

/** Runs one long task at a time and exposes its state to the UI. Survives tab switches. */
class JobRunner(private val scope: CoroutineScope, private val appContext: Context) {
    var state by mutableStateOf<JobState>(JobState.Idle)
        private set
    private var job: Job? = null
    val busy: Boolean get() = state is JobState.Running

    fun update(message: String, progress: Float? = null) {
        state = JobState.Running(message, progress)
        KeepAlive.progress(appContext, message, progress)
    }

    fun start(initial: String, block: suspend JobRunner.() -> JobState) {
        job?.cancel()
        state = JobState.Running(initial)
        KeepAlive.begin(appContext)
        KeepAlive.progress(appContext, initial, null)
        job = scope.launch {
            state = try {
                block(this@JobRunner)
            } catch (e: CancellationException) {
                state = JobState.Idle
                throw e
            } catch (e: Throwable) {
                JobState.Failed(e.userMessage())
            }
        }
        job?.invokeOnCompletion { KeepAlive.end(appContext) }
    }

    fun cancel() {
        job?.cancel()
        state = JobState.Idle
    }

    fun clear() {
        if (!busy) state = JobState.Idle
    }
}

class AppState private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AppState? = null
        /** One state for the whole process, so work keeps running (and is still shown) after the screen is closed. */
        fun get(context: Context): AppState =
            instance ?: synchronized(this) { instance ?: AppState(context.applicationContext).also { instance = it } }
    }

    private val prefs = context.applicationContext.getSharedPreferences("voicecut", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Navigation
    var tab by mutableIntStateOf(0)
    var showSettings by mutableStateOf(false)

    // Shared project data: import once, use on every tab.
    var media by mutableStateOf<Uri?>(null)
    var segments by mutableStateOf<List<Segment>>(emptyList())
    var scriptName by mutableStateOf("")
    // Output of the Transcribe tab only; the Voice tab keeps its own script in `segments`.
    var transcript by mutableStateOf<List<Segment>>(emptyList())
    var transcriptName by mutableStateOf("")
    var transcriptMedia by mutableStateOf<Uri?>(null) // the file the transcript came from (for playing clips)
    val clip = ClipPlayer()
    var originalSegments by mutableStateOf<List<Segment>?>(null) // kept so a translation can be undone
    var generatedAudio by mutableStateOf<Uri?>(null)
    val voiceChoices = mutableStateMapOf<String, String>()
    val speakerGender = mutableStateMapOf<String, String>() // manual male/female choice per speaker name

    // Saved API keys
    var assemblyKey by mutableStateOf(prefs.getString("aai_key", "") ?: "")
        private set
    var openAiKey by mutableStateOf(prefs.getString("openai_key", "") ?: "")
        private set

    var elevenKey by mutableStateOf(prefs.getString("eleven_key", "") ?: "")
        private set

    fun saveElevenKey(v: String) {
        elevenKey = v.trim()
        prefs.edit().putString("eleven_key", elevenKey).apply()
    }

    fun saveAssemblyKey(v: String) {
        assemblyKey = v.trim()
        prefs.edit().putString("aai_key", assemblyKey).apply()
    }

    fun saveOpenAiKey(v: String) {
        openAiKey = v.trim()
        prefs.edit().putString("openai_key", openAiKey).apply()
    }

    val extractJob = JobRunner(scope, context.applicationContext)
    val cutJob = JobRunner(scope, context.applicationContext)
    val translateJob = JobRunner(scope, context.applicationContext)
    val transcribeJob = JobRunner(scope, context.applicationContext)
    val voiceJob = JobRunner(scope, context.applicationContext)
    val timingJob = JobRunner(scope, context.applicationContext)
    val editJob = JobRunner(scope, context.applicationContext)
}
