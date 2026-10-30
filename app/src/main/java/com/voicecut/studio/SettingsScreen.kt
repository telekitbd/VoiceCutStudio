@file:OptIn(ExperimentalMaterial3Api::class)

package com.voicecut.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(s: AppState) {
    ScreenIntro("Settings", "API keys are stored privately on this device only.")
    ApiKeyCard(
        title = "AssemblyAI · speech to text",
        description = "Needed for the Transcribe tab.",
        linkLabel = "Get a key at assemblyai.com",
        url = "https://www.assemblyai.com/dashboard/signup",
        saved = s.assemblyKey,
        onSave = s::saveAssemblyKey
    )
    ApiKeyCard(
        title = "OpenAI · cloud voices (optional)",
        description = "Only for natural-sounding cloud voices. Device voices need no key.",
        linkLabel = "Get a key at platform.openai.com",
        url = "https://platform.openai.com/api-keys",
        saved = s.openAiKey,
        onSave = s::saveOpenAiKey
    )
    SectionCard("Privacy & files") {
        Text("• Audio you transcribe is uploaded to AssemblyAI. Text for cloud voices is sent to OpenAI.", style = MaterialTheme.typography.bodyMedium)
        Text("• Everything you create is saved in Downloads/VoiceCut.", style = MaterialTheme.typography.bodyMedium)
        Text("• Keys are excluded from device backups.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ApiKeyCard(
    title: String, description: String, linkLabel: String, url: String,
    saved: String, onSave: (String) -> Unit
) {
    val ctx = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(saved) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val changed = draft.trim() != saved

    SectionCard(title) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text("API key") },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide key")
                }
            },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft) }, enabled = changed) {
                Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save")
            }
            if (saved.isNotEmpty()) OutlinedButton(onClick = { draft = ""; onSave("") }) { Text("Remove") }
            Spacer(Modifier.weight(1f))
            if (saved.isNotEmpty() && !changed) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(4.dp))
                Text("Saved · …${saved.takeLast(4)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(onClick = { openWeb(ctx, url) }, contentPadding = PaddingValues(0.dp)) { Text(linkLabel) }
    }
}
