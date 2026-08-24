package com.vikash.voicescribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vikash.voicescribe.App
import com.vikash.voicescribe.model.DownloadState
import com.vikash.voicescribe.model.ModelManager
import com.vikash.voicescribe.model.WhisperModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(app: App, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val downloadState by app.models.downloadState.collectAsState()
    val installed by app.models.installedIds.collectAsState()
    var selected by remember { mutableStateOf(app.models.selectedId) }
    val recommended = app.models.recommended()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcription model") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Models run entirely on this phone. Recordings never leave your device — " +
                        "transcription works in airplane mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ModelManager.CATALOG, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isInstalled = model.id in installed,
                    isSelected = selected == model.id,
                    isRecommended = model.id == recommended.id,
                    downloadState = downloadState,
                    onSelect = {
                        selected = model.id
                        app.models.selectedId = model.id
                    },
                    onDownload = {
                        scope.launch {
                            if (app.models.download(model)) {
                                selected = model.id
                                app.engine.retryPending()
                            }
                        }
                    },
                    onDelete = { app.models.deleteModel(model) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: WhisperModel,
    isInstalled: Boolean,
    isSelected: Boolean,
    isRecommended: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloadingThis = (downloadState as? DownloadState.Downloading)?.modelId == model.id
    val failedThis = (downloadState as? DownloadState.Failed)?.modelId == model.id
    val anyDownloading = downloadState is DownloadState.Downloading

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isInstalled) {
                    RadioButton(selected = isSelected, onClick = onSelect)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (isRecommended) {
                            Text(
                                "  · recommended for this phone",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        "${model.sizeMB} MB — ${model.description}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                downloadingThis -> {
                    val progress = (downloadState as DownloadState.Downloading).progress
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Downloading… ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                isInstalled -> Row {
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
                else -> {
                    if (failedThis) {
                        Text(
                            (downloadState as DownloadState.Failed).message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onDownload, enabled = !anyDownloading) {
                        Text(if (failedThis) "Retry download" else "Download")
                    }
                }
            }
        }
    }
}
