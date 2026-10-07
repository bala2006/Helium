package com.sekhar.helium.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sekhar.helium.core.model.CloudAnalysisMode
import com.sekhar.helium.core.model.ExportPreset
import com.sekhar.helium.core.model.ImageDetail
import com.sekhar.helium.core.model.ReasoningLevel
import com.sekhar.helium.core.ui.components.HeliumChip
import com.sekhar.helium.core.ui.components.HeliumSecondaryButton
import com.sekhar.helium.core.ui.components.HeliumSectionHeader
import com.sekhar.helium.core.ui.theme.HeliumColors
import com.sekhar.helium.core.ui.theme.HeliumTextStyles

/**
 * Settings.
 *
 * Grouped by the question the user is actually asking — where does my AI edit go,
 * how much of my video may leave the phone, and what does export do by default —
 * because those are the three things a video editor must be honest about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onReasoningChange: (ReasoningLevel) -> Unit,
    onImageDetailChange: (ImageDetail) -> Unit,
    onCloudModeChange: (CloudAnalysisMode) -> Unit,
    onMaxIterationsChange: (Int) -> Unit,
    onTelemetryChange: (Boolean) -> Unit,
    onProxyPreviewChange: (Boolean) -> Unit,
    onAutoCaptionChange: (Boolean) -> Unit,
    onDefaultPresetChange: (ExportPreset) -> Unit,
    onCheckBackend: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val settings = state.settings
    val config = settings.aiModel

    Scaffold(
        containerColor = HeliumColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back", color = HeliumColors.OnSurfaceMuted) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeliumColors.Background,
                    titleContentColor = HeliumColors.OnBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            state.savedMessage?.let { message ->
                HeliumChip(text = message, accent = HeliumColors.Success)
                Spacer(Modifier.height(10.dp))
                LaunchedDismiss(onDismissMessage)
            }

            // ---------------------------------------------------------------------
            Spacer(Modifier.height(4.dp))
            HeliumSectionHeader(text = "AI gateway")
            Text(
                text = "Helium has no provider key inside the app. Your phone talks to your own " +
                    "gateway, and the gateway talks to the model.",
                style = MaterialTheme.typography.bodyMedium,
                color = HeliumColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = settings.backendBaseUrl,
                onValueChange = onBackendUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gateway base URL") },
                placeholder = { Text("https://helium-gateway.example.com") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = heliumFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeliumSecondaryButton(text = "Test connection", onClick = onCheckBackend)
                when (val health = state.health) {
                    is BackendHealth.Reachable -> HeliumChip(
                        text = "Reachable in ${health.latencyMs} ms",
                        accent = HeliumColors.Success,
                    )
                    is BackendHealth.Unreachable -> HeliumChip(
                        text = health.message,
                        accent = HeliumColors.Error,
                    )
                    BackendHealth.Checking -> HeliumChip(text = "Checking…", accent = HeliumColors.Amber)
                    BackendHealth.Unknown -> Unit
                }
            }

            // ---------------------------------------------------------------------
            Spacer(Modifier.height(22.dp))
            HeliumSectionHeader(text = "Model")
            Text(
                text = "The editor engine is model-agnostic. Change these at any time — the " +
                    "timeline never depends on which model planned an edit.",
                style = MaterialTheme.typography.bodyMedium,
                color = HeliumColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = config.modelId,
                onValueChange = onModelIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model id") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = heliumFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsViewModel.SUGGESTED_MODELS.forEach { model ->
                    HeliumChip(
                        text = model,
                        accent = if (model == config.modelId) HeliumColors.Blossom else HeliumColors.Plum,
                        modifier = Modifier.clickable { onModelIdChange(model) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ChoiceRow(
                label = "Reasoning level",
                options = ReasoningLevel.entries.map { it.wireValue },
                selected = config.reasoningLevel.wireValue,
                onSelect = { wire ->
                    ReasoningLevel.entries.firstOrNull { it.wireValue == wire }?.let(onReasoningChange)
                },
            )
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                label = "Image detail sent with evidence",
                options = ImageDetail.entries.map { it.wireValue },
                selected = config.imageDetail.wireValue,
                onSelect = { wire ->
                    ImageDetail.entries.firstOrNull { it.wireValue == wire }?.let(onImageDetailChange)
                },
            )
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                label = "Tool loop limit (max inspections per instruction)",
                options = listOf("4", "8", "12", "20"),
                selected = config.maxToolIterations.toString(),
                onSelect = { value -> value.toIntOrNull()?.let(onMaxIterationsChange) },
            )

            // ---------------------------------------------------------------------
            Spacer(Modifier.height(22.dp))
            HeliumSectionHeader(text = "Privacy")
            Text(
                text = "Analysis, proxying and OCR happen on your phone. Only the frames and text " +
                    "the AI actually needs to answer you are ever transmitted — never the full video.",
                style = MaterialTheme.typography.bodyMedium,
                color = HeliumColors.OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
            CloudAnalysisMode.entries.forEach { mode ->
                SelectionCard(
                    title = mode.displayName,
                    body = when (mode) {
                        CloudAnalysisMode.MINIMIZE ->
                            "Text only. No frames leave the device at all, even if the AI asks."
                        CloudAnalysisMode.BALANCED ->
                            "Text plus a few low-resolution frames from the sections being inspected."
                        CloudAnalysisMode.FULL ->
                            "Also allows an original-resolution frame when proxy evidence is not enough."
                    },
                    selected = config.cloudAnalysisMode == mode,
                    onClick = { onCloudModeChange(mode) },
                )
            }
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Anonymous performance telemetry",
                body = "Timings and error codes only. Never transcripts, frames or prompts.",
                checked = settings.telemetryEnabled,
                onCheckedChange = onTelemetryChange,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Fast proxy preview",
                body = "Preview a low-resolution copy while editing; exports always use the original.",
                checked = settings.useProxyForPreview,
                onCheckedChange = onProxyPreviewChange,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Try captions on import",
                body = "Runs the transcript stage as soon as a clip is imported.",
                checked = settings.autoCaptionOnImport,
                onCheckedChange = onAutoCaptionChange,
            )

            // ---------------------------------------------------------------------
            Spacer(Modifier.height(22.dp))
            HeliumSectionHeader(text = "Export")
            Spacer(Modifier.height(6.dp))
            ExportPreset.entries.forEach { preset ->
                SelectionCard(
                    title = preset.displayName,
                    body = "H.264 video · AAC audio · MP4 container",
                    selected = settings.defaultExportPreset == preset,
                    onClick = { onDefaultPresetChange(preset) },
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Helium keeps your originals untouched. Deleting a project removes only " +
                    "Helium's own metadata and analysis cache.",
                style = HeliumTextStyles.Timestamp,
                color = HeliumColors.OnSurfaceFaint,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LaunchedDismiss(onDismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2_200)
        onDismiss()
    }
}

@Composable
private fun heliumFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = HeliumColors.SurfaceElevated,
    unfocusedContainerColor = HeliumColors.SurfaceElevated,
    focusedBorderColor = HeliumColors.Blossom,
    unfocusedBorderColor = HeliumColors.Outline,
    focusedTextColor = HeliumColors.OnBackground,
    unfocusedTextColor = HeliumColors.OnBackground,
    focusedLabelColor = HeliumColors.Blossom,
    unfocusedLabelColor = HeliumColors.OnSurfaceFaint,
    cursorColor = HeliumColors.Blossom,
)

@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = HeliumTextStyles.Timestamp,
            color = HeliumColors.OnSurfaceFaint,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                HeliumChip(
                    text = option,
                    accent = if (option == selected) HeliumColors.Blossom else HeliumColors.Outline,
                    modifier = Modifier.clickable { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun SelectionCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) HeliumColors.SurfaceHighest else HeliumColors.SurfaceElevated)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) HeliumColors.Blossom else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = HeliumColors.OnBackground,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                style = HeliumTextStyles.Timestamp,
                color = HeliumColors.OnSurfaceFaint,
            )
        }
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) HeliumColors.Blossom else HeliumColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = HeliumColors.SurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HeliumColors.OnBackground,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = body,
                    style = HeliumTextStyles.Timestamp,
                    color = HeliumColors.OnSurfaceFaint,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = HeliumColors.Blossom,
                    uncheckedTrackColor = HeliumColors.SurfaceHighest,
                    uncheckedThumbColor = HeliumColors.OnSurfaceMuted,
                ),
            )
        }
    }
}
