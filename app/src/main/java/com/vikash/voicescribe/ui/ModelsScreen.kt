package com.vikash.voicescribe.ui

import android.content.Context
import android.net.ConnectivityManager
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
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
    val isPro by app.billing.isPro.collectAsState()
    var selected by remember { mutableStateOf(app.models.selectedId) }
    var paywall by remember { mutableStateOf(false) }
    var meteredWarning by remember { mutableStateOf<WhisperModel?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recommended = app.models.recommended()

    fun startDownload(model: WhisperModel) {
        scope.launch {
            if (app.models.download(model)) {
                selected = model.id
                app.engine.retryPending()
            }
        }
    }

    fun requestDownload(model: WhisperModel) {
        if (model.pro && !isPro) {
            paywall = true
            return
        }
        // Big downloads on mobile data deserve a heads-up — data is metered
        // in exactly the markets this app targets.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (model.sizeMB >= 100 && cm.isActiveNetworkMetered) {
            meteredWarning = model
        } else {
            startDownload(model)
        }
    }

    if (paywall) {
        PaywallDialog(billing = app.billing, onDismiss = { paywall = false })
    }

    meteredWarning?.let { model ->
        AlertDialog(
            onDismissRequest = { meteredWarning = null },
            title = { Text("Download on mobile data?") },
            text = {
                Text(
                    "${model.label} is a ${model.sizeMB} MB download and you're not on Wi-Fi. " +
                        "This may use a large part of your data plan."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    meteredWarning = null
                    startDownload(model)
                }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { meteredWarning = null }) { Text("Wait for Wi-Fi") }
            },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("About & licenses") },
            text = {
                Text(
                    "VoiceScribe transcribes with whisper.cpp by Georgi Gerganov and " +
                        "contributors (MIT License) and OpenAI Whisper models. Speaker " +
                        "detection uses tinydiarize by Akash Mahajan (MIT License).\n\n" +
                        "MIT License: permission is granted, free of charge, to use, copy, " +
                        "modify, and distribute the software; the software is provided " +
                        "“as is”, without warranty of any kind. Full texts: " +
                        "github.com/ggml-org/whisper.cpp and github.com/akashmjn/tinydiarize."
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Close") }
            },
        )
    }

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
                    isLocked = model.pro && !isPro,
                    downloadState = downloadState,
                    onSelect = {
                        selected = model.id
                        app.models.selectedId = model.id
                    },
                    onDownload = { requestDownload(model) },
                    onDelete = { app.models.deleteModel(model) },
                )
            }
            item {
                TextButton(onClick = { showLicenses = true }) {
                    Text("About & open-source licenses")
                }
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
    isLocked: Boolean,
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
                        if (model.pro) {
                            Text(
                                "  PRO",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
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
                        Text(
                            when {
                                isLocked -> "Unlock to download"
                                failedThis -> "Retry download"
                                else -> "Download"
                            }
                        )
                    }
                }
            }
        }
    }
}
