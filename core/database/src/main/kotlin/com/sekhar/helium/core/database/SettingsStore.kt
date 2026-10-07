package com.sekhar.helium.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sekhar.helium.core.common.HeliumJson
import com.sekhar.helium.core.model.AiModelConfig
import com.sekhar.helium.core.model.CloudAnalysisMode
import com.sekhar.helium.core.model.ExportPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * User-visible settings.
 *
 * [aiModel] is stored as a JSON document rather than individual keys so a new
 * remotely-selected model option never needs a schema migration.
 */
data class HeliumSettings(
    val aiModel: AiModelConfig = AiModelConfig(),
    val backendBaseUrl: String = "",
    val autoCaptionOnImport: Boolean = false,
    val defaultExportPreset: ExportPreset = ExportPreset.ORIGINAL,
    /** Anonymous, content-free crash and timing telemetry. */
    val telemetryEnabled: Boolean = true,
    /** Preview from the low-resolution proxy (fast) instead of the original. */
    val useProxyForPreview: Boolean = true,
) {
    val cloudAnalysisMode: CloudAnalysisMode get() = aiModel.cloudAnalysisMode
}

interface SettingsStore {
    val settings: Flow<HeliumSettings>
    suspend fun update(transform: (HeliumSettings) -> HeliumSettings)
    suspend fun setAiModel(config: AiModelConfig)
    suspend fun setBackendBaseUrl(url: String)
    suspend fun setAutoCaptionOnImport(enabled: Boolean)
    suspend fun setDefaultExportPreset(preset: ExportPreset)
    suspend fun setTelemetryEnabled(enabled: Boolean)
    suspend fun setUseProxyForPreview(enabled: Boolean)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "helium_settings")

class DataStoreSettingsStore(private val context: Context) : SettingsStore {

    override val settings: Flow<HeliumSettings> = context.settingsDataStore.data.map { preferences ->
        HeliumSettings(
            aiModel = preferences[KEY_AI_MODEL]?.let { raw ->
                runCatching { HeliumJson.decodeFromString<AiModelConfig>(raw) }.getOrNull()
            } ?: AiModelConfig(),
            backendBaseUrl = preferences[KEY_BACKEND_URL].orEmpty(),
            autoCaptionOnImport = preferences[KEY_AUTO_CAPTION] ?: false,
            defaultExportPreset = preferences[KEY_EXPORT_PRESET]
                ?.let { name -> ExportPreset.entries.firstOrNull { it.name == name } }
                ?: ExportPreset.ORIGINAL,
            telemetryEnabled = preferences[KEY_TELEMETRY] ?: true,
            useProxyForPreview = preferences[KEY_USE_PROXY] ?: true,
        )
    }

    override suspend fun update(transform: (HeliumSettings) -> HeliumSettings) {
        val current = firstSettings()
        val updated = transform(current)
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_AI_MODEL] = HeliumJson.encodeToString(updated.aiModel)
            preferences[KEY_BACKEND_URL] = updated.backendBaseUrl
            preferences[KEY_AUTO_CAPTION] = updated.autoCaptionOnImport
            preferences[KEY_EXPORT_PRESET] = updated.defaultExportPreset.name
            preferences[KEY_TELEMETRY] = updated.telemetryEnabled
            preferences[KEY_USE_PROXY] = updated.useProxyForPreview
        }
    }

    override suspend fun setAiModel(config: AiModelConfig) = update { it.copy(aiModel = config) }

    override suspend fun setBackendBaseUrl(url: String) = update { it.copy(backendBaseUrl = url.trim()) }

    override suspend fun setAutoCaptionOnImport(enabled: Boolean) = update { it.copy(autoCaptionOnImport = enabled) }

    override suspend fun setDefaultExportPreset(preset: ExportPreset) = update { it.copy(defaultExportPreset = preset) }

    override suspend fun setTelemetryEnabled(enabled: Boolean) = update { it.copy(telemetryEnabled = enabled) }

    override suspend fun setUseProxyForPreview(enabled: Boolean) = update { it.copy(useProxyForPreview = enabled) }

    /**
     * Reads the current settings once.
     *
     * `settings` is cached by DataStore, so this is a cheap read rather than a
     * disk hit, and it always reflects the most recent committed write.
     */
    private suspend fun firstSettings(): HeliumSettings = settings.first()

    private companion object {
        val KEY_AI_MODEL = stringPreferencesKey("ai_model_json")
        val KEY_BACKEND_URL = stringPreferencesKey("backend_base_url")
        val KEY_AUTO_CAPTION = booleanPreferencesKey("auto_caption_on_import")
        val KEY_EXPORT_PRESET = stringPreferencesKey("default_export_preset")
        val KEY_TELEMETRY = booleanPreferencesKey("telemetry_enabled")
        val KEY_USE_PROXY = booleanPreferencesKey("use_proxy_for_preview")
    }
}
