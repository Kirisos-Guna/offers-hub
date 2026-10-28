package com.offershub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Slim card at the top of the feed when a newer version is out. */
@Composable
fun UpdateBanner(version: String, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available", style = MaterialTheme.typography.titleSmall)
                Text("Version $version", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onUpdate) { Text("Update") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Not now") }
        }
    }
}

/** Download progress and install step; also shows "what's new". */
@Composable
fun UpdateDialog(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit, onClose: () -> Unit) {
    val update = when (state) {
        is UpdateState.Available -> state.update
        is UpdateState.Downloading -> state.update
        is UpdateState.Ready -> state.update
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (state !is UpdateState.Downloading) onClose() },
        title = { Text(if (update != null) "Version ${update.version}" else "Update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is UpdateState.Downloading -> {
                        Text("Downloading… ${(state.progress * 100).toInt()}%")
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Ready -> Text(
                        "Downloaded. Tap Install. The first time, Android asks you to allow Offers Hub to install updates."
                    )
                    is UpdateState.Failed -> Text(state.message)
                    else -> {}
                }
                if (update != null && update.notes.isNotBlank() && state !is UpdateState.Downloading) {
                    Text("What's new", style = MaterialTheme.typography.titleSmall)
                    Text(
                        update.notes.replace("### ", "").replace("**", ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(onClick = onDownload) { Text("Download") }
                is UpdateState.Ready -> Button(onClick = onInstall) { Text("Install") }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading) TextButton(onClick = onClose) { Text("Later") }
        },
    )
}
