package com.sekhar.helium.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sekhar.helium.core.database.HeliumSettings
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.CloudAnalysisMode
import com.sekhar.helium.core.model.ExportPreset
import com.sekhar.helium.core.model.ImageDetail
import com.sekhar.helium.core.model.ReasoningLevel
import com.sekhar.helium.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reachability of the Helium gateway. */
sealed interface BackendHealth {
    data object Unknown : BackendHealth
    data object Checking : BackendHealth
    data class Reachable(val latencyMs: Long) : BackendHealth
    data class Unreachable(val message: String) : BackendHealth
}

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: HeliumSettings = HeliumSettings(),
    val health: BackendHealth = BackendHealth.Unknown,
    val savedMessage: String? = null,
)

/**
 * Settings: where the gateway lives, which model to ask for, how much of the video
 * may leave the device, and what the default export looks like.
 *
 * The model configuration is stored as data — provider id, model id, reasoning
 * level, image detail and limits — so a new model can be adopted without an app
 * update and so the privacy posture is visible and switchable by the user.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { settings ->
                _state.update { it.copy(loading = false, settings = settings) }
            }
        }
    }

    fun setBackendUrl(url: String) = updateSettings(message = null) { it.copy(backendBaseUrl = url.trim()) }

    fun setModelId(modelId: String) = updateModel { it.copy(modelId = modelId.trim()) }

    fun setReasoningLevel(level: ReasoningLevel) = updateModel { it.copy(reasoningLevel = level) }

    fun setImageDetail(detail: ImageDetail) = updateModel { it.copy(imageDetail = detail) }

    fun setCloudAnalysisMode(mode: CloudAnalysisMode) = updateModel { config ->
        config.copy(
            cloudAnalysisMode = mode,
            // Minimising cloud analysis also drops the per-request image budget;
            // the dispatcher enforces this independently, but keeping the config
            // honest means the settings screen never lies about what will happen.
            maxImagesPerRequest = when (mode) {
                CloudAnalysisMode.MINIMIZE -> 0
                CloudAnalysisMode.BALANCED -> 8
                CloudAnalysisMode.FULL -> 16
            },
            // High-resolution pixels are only ever sent in FULL mode.
            imageDetail = if (mode == CloudAnalysisMode.FULL) ImageDetail.HIGH else ImageDetail.LOW,
        )
    }

    fun setMaxToolIterations(iterations: Int) = updateModel {
        it.copy(maxToolIterations = iterations.coerceIn(1, 64))
    }

    fun setAllowEscalation(enabled: Boolean) = updateModel {
        it.copy(
            allowEscalation = enabled,
            escalationModelId = if (enabled) {
                it.escalationModelId ?: DEFAULT_ESCALATION_MODEL
            } else {
                it.escalationModelId
            },
        )
    }

    fun setTelemetry(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setTelemetryEnabled(enabled)
            confirm(if (enabled) "Anonymous timing telemetry on" else "Telemetry off")
        }
    }

    fun setUseProxyForPreview(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setUseProxyForPreview(enabled)
            confirm(if (enabled) "Preview uses the fast proxy" else "Preview uses the original file")
        }
    }

    fun setAutoCaptionOnImport(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsStore.setAutoCaptionOnImport(enabled)
            confirm(if (enabled) "Captions on import" else "Captions off on import")
        }
    }

    fun setDefaultExportPreset(preset: ExportPreset) {
        viewModelScope.launch {
            container.settingsStore.setDefaultExportPreset(preset)
            confirm("Default export: ${preset.displayName}")
        }
    }

    /** Probes the gateway so the user can tell a bad URL from a broken network. */
    fun checkBackend() {
        viewModelScope.launch {
            _state.update { it.copy(health = BackendHealth.Checking) }
            val started = container.time.nowEpochMs()
            val reachable = runCatching { container.gatewayClient.ping() }.getOrDefault(false)
            val elapsed = container.time.nowEpochMs() - started
            _state.update {
                it.copy(
                    health = if (reachable) {
                        BackendHealth.Reachable(elapsed)
                    } else {
                        BackendHealth.Unreachable(
                            "No response from ${it.settings.backendBaseUrl.ifBlank { "the gateway" }}",
                        )
                    },
                )
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(savedMessage = null) }

    private fun updateModel(transform: (AiModelConfig) -> AiModelConfig) {
        viewModelScope.launch {
            val updated = transform(state.value.settings.aiModel)
            container.settingsStore.setAiModel(updated)
            confirm("AI settings saved")
        }
    }

    private fun updateSettings(message: String?, transform: (HeliumSettings) -> HeliumSettings) {
        viewModelScope.launch {
            container.settingsStore.update(transform)
            if (message != null) confirm(message)
        }
    }

    private fun confirm(message: String) = _state.update { it.copy(savedMessage = message) }

    companion object {
        /** Reasonable second-opinion model for genuinely hard prompts. */
        const val DEFAULT_ESCALATION_MODEL = "gpt-6-luna-deep"

        /** Offered as a shortcut; the field stays free-form so any model id works. */
        val SUGGESTED_MODELS = listOf("gpt-6-luna", "gpt-6-luna-deep")
    }
}
