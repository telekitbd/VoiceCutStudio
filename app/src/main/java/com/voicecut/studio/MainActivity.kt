@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AppState.get(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        setContent { VoiceCutTheme { App(state) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Files shared from other apps ("Share" / "Open with") land in the shared import slot. */
    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri != null) {
            state.media = uri
            state.tab = 0
            state.showSettings = false
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Extract", Icons.Rounded.GraphicEq),
    Tab("Transcribe", Icons.Rounded.Subtitles),
    Tab("Voice", Icons.Rounded.RecordVoiceOver),
    Tab("Video", Icons.Rounded.Movie),
)

@Composable
fun App(s: AppState) {
    val holder = rememberSaveableStateHolder()
    BackHandler(s.showSettings) { s.showSettings = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (s.showSettings) "Settings" else "VoiceCut Studio", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (s.showSettings) IconButton(onClick = { s.showSettings = false }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!s.showSettings) IconButton(onClick = { s.showSettings = true }) {
                        Icon(Icons.Rounded.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (!s.showSettings) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = s.tab == i, onClick = { s.tab = i },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        val page = if (s.showSettings) -1 else s.tab
        holder.SaveableStateProvider(page) {
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (page) {
                    -1 -> SettingsScreen(s)
                    0 -> ExtractScreen(s)
                    1 -> TranscribeScreen(s)
                    2 -> VoiceScreen(s)
                    else -> EditScreen(s)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
