package com.sekhar.helium.ui.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sekhar.helium.ai.AppToolDispatcher
import com.sekhar.helium.ai.agent.AgentRequest
import com.sekhar.helium.ai.agent.AgentStopReason
import com.sekhar.helium.core.database.HeliumSettings
import com.sekhar.helium.core.database.UsageEvent
import com.sekhar.helium.core.model.AgentStage
import com.sekhar.helium.core.model.AnalysisStage
import com.sekhar.helium.core.model.ClipId
import com.sekhar.helium.core.model.EditOperation
import com.sekhar.helium.core.model.EditTransaction
import com.sekhar.helium.core.model.ExportPreset
import com.sekhar.helium.core.model.ExportState
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.ProjectId
import com.sekhar.helium.core.model.SemanticVideoMemory
import com.sekhar.helium.core.model.SourceId
import com.sekhar.helium.core.model.SourceMedia
import com.sekhar.helium.core.model.SplitClip
import com.sekhar.helium.core.model.TransactionSource
import com.sekhar.helium.data.ExportPublisher
import com.sekhar.helium.data.MediaImporter
import com.sekhar.helium.di.AppContainer
import com.sekhar.helium.media.engine.ExportOutcome
import com.sekhar.helium.media.engine.ExportPlanBuilder
import com.sekhar.helium.media.engine.ExportRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Progress of one source's local semantic indexing run. */
data class IndexingProgress(
    val stage: AnalysisStage,
    val progress: Float,
    val message: String,
)

/** Everything the editor screen renders. */
data class EditorUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val project: Project? = null,
    val previewSegments: List<PreviewSegment> = emptyList(),
    /** Audio amplitude envelope of the edited timeline, drawn under the clips. */
    val waveform: List<Float> = emptyList(),
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipId: ClipId? = null,
    val zoom: Float = 1f,
    val prompt: String = "",
    val aiRunning: Boolean = false,
    val aiStage: AgentStage = AgentStage.IDLE,
    val aiStatus: String = "",
    val aiSummary: String? = null,
    val aiWarnings: List<String> = emptyList(),
    val indexing: Map<SourceId, IndexingProgress> = emptyMap(),
    val message: String? = null,
    val exportPreset: ExportPreset = ExportPreset.ORIGINAL,
    val exportState: ExportState = ExportState.Idle,
    /** Edits the engine could not bake into the last export; shown, never hidden. */
    val unsupportedFeatures: List<String> = emptyList(),
    val lastExportPath: String? = null,
    val lastExportUri: String? = null,
    val settings: HeliumSettings = HeliumSettings(),
) {
    val canUndo: Boolean get() = (project?.history?.canUndo ?: false)
    val canRedo: Boolean get() = (project?.history?.canRedo ?: false)
    val durationMs: Long get() = project?.timeline?.durationMs ?: 0L
    val hasMedia: Boolean get() = (project?.sources?.count { it.isUsable } ?: 0) > 0
    val appliedTransactions: List<EditTransaction>
        get() = project?.history?.appliedTransactions.orEmpty()
    val isExporting: Boolean
        get() = exportState is ExportState.Preparing || exportState is ExportState.Running
}

/**
 * Drives one editing session.
 *
 * The ViewModel owns the *sequence* of work (import → index → instruct → apply →
 * export) and nothing else: the timeline maths lives in `:editor:domain`, the
 * rendering lives behind `VideoEngine`, and the model conversation lives in
 * `:ai:agent`. That keeps this class about orchestration, which is the part that
 * genuinely has to know about all of them.
 *
 * Two invariants are enforced here rather than left to the UI:
 *
 * * **Every accepted edit is persisted before the UI shows it.** The project is
 *   saved after each successful transaction, so process death never loses an edit.
 * * **Only one AI instruction runs at a time,** and its result is applied as a
 *   single atomic transaction, so the user can always undo it.
 */
class EditorViewModel(
    private val container: AppContainer,
    private val projectId: ProjectId,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** In-memory cache of semantic memories; also the retrieval source for tools. */
    private val memories = mutableMapOf<String, SemanticVideoMemory>()

    private val indexingJobs = mutableMapOf<SourceId, Job>()
    private var agentJob: Job? = null
    private var exportJob: Job? = null
    private var settings: HeliumSettings = HeliumSettings()

    init {
        viewModelScope.launch {
            container.settingsStore.settings.collect { updated ->
                settings = updated
                _state.update { current ->
                    current.copy(
                        settings = updated,
                        exportPreset = current.project?.exportSettings?.preset ?: updated.defaultExportPreset,
                    )
                }
            }
        }
        viewModelScope.launch { loadProject() }
    }

    // -----------------------------------------------------------------------------
    // Load and persist
    // -----------------------------------------------------------------------------

    private suspend fun loadProject() {
        val project = container.projectStore.loadProject(projectId)
        if (project == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        _state.update {
            it.copy(
                loading = false,
                project = project,
                exportPreset = project.exportSettings.preset,
                playheadMs = 0L,
            )
        }
        publishDerived(project)
        project.usableSources.forEach { source -> startIndexing(source, refreshImmediately = true) }
    }

    private suspend fun persist(project: Project) {
        container.projectStore.saveProject(project)
    }

    /** Recomputes the derived view state (preview playlist + waveform). */
    private fun publishDerived(project: Project?) {
        val current = project ?: return
        val rms = memories.mapValues { (_, memory) ->
            memory.informationCurve.map { it.timestampMs to it.audioRms }
        }
        _state.update {
            it.copy(
                previewSegments = TimelinePreviewModel.segments(current.timeline),
                waveform = TimelinePreviewModel.waveform(current.timeline, rms),
            )
        }
    }

    // -----------------------------------------------------------------------------
    // Import + local analysis
    // -----------------------------------------------------------------------------

    /** Imports picked documents, then starts indexing each new source. */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val project = _state.value.project ?: return@launch
            _state.update { it.copy(message = "Importing ${uris.size} file(s)…") }
            val outcome = MediaImporter(container).import(uris, project)
            if (outcome.imported.isEmpty()) {
                _state.update {
                    it.copy(
                        message = outcome.warnings.firstOrNull()
                            ?: "That file could not be read as a video.",
                    )
                }
                return@launch
            }
            persist(outcome.project)
            _state.update {
                it.copy(
                    project = outcome.project,
                    message = buildString {
                        append("Imported ${outcome.imported.size} clip")
                        if (outcome.imported.size != 1) append('s')
                        append(". Analysing on device…")
                    },
                )
            }
            publishDerived(outcome.project)
            outcome.imported.forEach { startIndexing(it, refreshImmediately = false) }
            outcome.warnings.forEach { warning -> container.log.w(TAG, "Import warning: $warning") }
        }
    }

    /**
     * Runs the progressive local index for [source].
     *
     * A cached index short-circuits the whole pipeline, so re-opening a project
     * never re-analyses the same video. Each completed stage is republished into
     * the state so the UI can show progress, and the tool dispatcher can already
     * retrieve whatever has been computed so far.
     */
    private fun startIndexing(source: SourceMedia, refreshImmediately: Boolean) {
        if (!source.isUsable) return
        if (indexingJobs[source.id]?.isActive == true) return
        indexingJobs[source.id] = viewModelScope.launch {
            try {
                val cached = container.semanticIndexer.cached(source)
                if (cached != null) {
                    memories[source.id.value] = cached
                    publishDerived(_state.value.project)
                    return@launch
                }
                if (refreshImmediately) {
                    container.semanticMemoryStore.loadForSource(source.id)?.let { existing ->
                        memories[source.id.value] = existing
                        publishDerived(_state.value.project)
                    }
                }
                container.semanticIndexer.index(source) { stage, progress ->
                    _state.update { current ->
                        current.copy(
                            indexing = current.indexing + (
                                source.id to IndexingProgress(stage, progress, stage.displayName)
                                ),
                        )
                    }
                    if (stage == AnalysisStage.COMPLETE) {
                        // Refresh the retrieval cache once the index is final.
                        viewModelScope.launch { refreshMemory(source.id) }
                    }
                }
                refreshMemory(source.id)
                _state.update { it.copy(indexing = it.indexing - source.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                container.log.w(TAG, "Indexing failed for ${source.id.value}: ${failure::class.simpleName}")
                _state.update { current ->
                    current.copy(
                        indexing = current.indexing - source.id,
                        message = "Analysis of ${source.displayName} stopped early. " +
                            "The clip is still editable and exportable.",
                    )
                }
            }
        }
    }

    private suspend fun refreshMemory(sourceId: SourceId) {
        val memory = container.semanticMemoryStore.loadForSource(sourceId) ?: return
        memories[sourceId.value] = memory
        publishDerived(_state.value.project)
    }

    /** Re-runs analysis for a source, e.g. after the user installs transcription. */
    fun reindexAll() {
        val project = _state.value.project ?: return
        project.usableSources.forEach { startIndexing(it, refreshImmediately = false) }
    }

    // -----------------------------------------------------------------------------
    // Playback + selection (pure UI state; the player lives in the screen)
    // -----------------------------------------------------------------------------

    fun seekTo(timelineMs: Long) {
        _state.update { it.copy(playheadMs = timelineMs.coerceIn(0L, it.durationMs)) }
    }

    fun togglePlay() = _state.update { it.copy(isPlaying = !it.isPlaying) }

    fun stopPlayback() = _state.update { it.copy(isPlaying = false) }

    fun onPlayerPosition(timelineMs: Long) {
        _state.update { current ->
            if (current.isPlaying) current.copy(playheadMs = timelineMs.coerceIn(0L, current.durationMs))
            else current
        }
    }

    fun selectClip(clipId: ClipId?) = _state.update { it.copy(selectedClipId = clipId) }

    fun setZoom(zoom: Float) = _state.update { it.copy(zoom = zoom.coerceIn(0.5f, 8f)) }

    fun onPromptChange(value: String) = _state.update { it.copy(prompt = value.take(MAX_PROMPT_CHARS)) }

    fun dismissMessage() = _state.update { it.copy(message = null, aiSummary = null, aiWarnings = emptyList()) }

    // -----------------------------------------------------------------------------
    // Manual edits
    // -----------------------------------------------------------------------------

    /** Splits the clip under the playhead at the playhead's source offset. */
    fun splitAtPlayhead() {
        val project = _state.value.project ?: return
        val clip = TimelinePreviewModel.clipAt(project.timeline, _state.value.playheadMs)
        if (clip == null) {
            _state.update { it.copy(message = "Move the playhead over a clip to split it.") }
            return
        }
        val offsetInClip = _state.value.playheadMs - clip.timelineStartMs
        val sourceOffset = clip.sourceRange.startMs + clip.timelineOffsetToSourceOffset(offsetInClip)
        if (sourceOffset <= clip.sourceRange.startMs + MIN_SPLIT_MS ||
            sourceOffset >= clip.sourceRange.endMsExclusive - MIN_SPLIT_MS
        ) {
            _state.update { it.copy(message = "Move the playhead at least ${MIN_SPLIT_MS / 1000f}s from a clip edge.") }
            return
        }
        applyOperations(
            project = project,
            userPrompt = null,
            operations = listOf(SplitClip(container.ids.newId(), clip.id, sourceOffset)),
            source = TransactionSource.MANUAL,
        )
    }

    fun undo() = historyStep { container.editEngine.undo(it) }
    fun redo() = historyStep { container.editEngine.redo(it) }

    private fun historyStep(block: (Project) -> com.sekhar.helium.editor.domain.ProjectEdit) {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            val edit = block(project)
            if (!edit.outcome.success) {
                _state.update { it.copy(message = edit.outcome.summary) }
                return@launch
            }
            persist(edit.project)
            _state.update {
                it.copy(project = edit.project, message = edit.outcome.summary, playheadMs = it.playheadMs)
            }
            publishDerived(edit.project)
        }
    }

    // -----------------------------------------------------------------------------
    // AI instruction
    // -----------------------------------------------------------------------------

    /**
     * Runs one natural-language instruction through the bounded agent loop and
     * applies the resulting operations as a single atomic transaction.
     */
    fun submitPrompt() {
        val project = _state.value.project ?: return
        val prompt = _state.value.prompt.trim()
        if (prompt.isEmpty()) return
        if (_state.value.aiRunning) return
        if (project.sources.isEmpty()) {
            _state.update { it.copy(message = "Import a video before asking the AI to edit.") }
            return
        }

        val provider = container.aiProviders.resolve(settings.aiModel)
        if (provider == null) {
            _state.update { it.copy(message = "No AI provider is configured. Check Settings.") }
            return
        }

        _state.update {
            it.copy(
                aiRunning = true,
                aiStage = AgentStage.UNDERSTANDING,
                aiStatus = "Understanding your request",
                aiSummary = null,
                aiWarnings = emptyList(),
                prompt = "",
            )
        }

        agentJob = viewModelScope.launch {
            val config = settings.aiModel
            val context = EditorContextBuilder.build(
                project = project,
                memories = memories.toMap(),
                indexing = _state.value.indexing,
                config = config,
            )
            val dispatcher = AppToolDispatcher(
                project = project,
                memories = memories.toMap(),
                config = config,
                log = container.log,
            )
            val agent = container.createAgent(dispatcher)
            val idempotencyKey = "edit-${project.id.value}-${project.revision}-${container.ids.newId()}"

            val result = agent.run(
                request = AgentRequest(
                    userPrompt = prompt,
                    context = context,
                    config = config,
                    idempotencyKey = idempotencyKey,
                ),
                onProgress = { progress ->
                    _state.update {
                        it.copy(
                            aiStage = progress.stage,
                            aiStatus = progress.message.ifBlank { it.aiStatus },
                        )
                    }
                },
            )

            runCatching {
                container.usageStore.record(
                    UsageEvent(
                        id = container.ids.newId(),
                        projectId = project.id,
                        timestampEpochMs = container.time.nowEpochMs(),
                        providerId = provider.id,
                        modelId = config.modelId,
                        inputTokens = result.usage.tokens.inputTokens,
                        outputTokens = result.usage.tokens.outputTokens,
                        reasoningTokens = result.usage.tokens.reasoningTokens,
                        imagesSent = result.usage.imagesSent,
                        toolCalls = result.usage.toolCalls,
                        toolFailures = result.usage.toolFailures,
                        latencyMs = result.usage.latencyMs,
                        succeeded = result.stopReason != AgentStopReason.PROVIDER_ERROR,
                    ),
                )
            }

            _state.update {
                it.copy(aiRunning = false, aiStage = AgentStage.IDLE, aiStatus = "", aiWarnings = result.errors)
            }

            if (!result.producedEdits) {
                _state.update { it.copy(aiSummary = result.summary) }
                return@launch
            }

            applyOperations(
                project = project,
                userPrompt = prompt,
                operations = result.operations,
                source = TransactionSource.AI,
                assistantNote = result.assistantText.takeIf { text -> text.isNotBlank() },
            )
        }
    }

    fun cancelPrompt() {
        agentJob?.cancel()
        agentJob = null
        _state.update {
            it.copy(aiRunning = false, aiStage = AgentStage.IDLE, aiStatus = "", message = "Cancelled.")
        }
    }

    private fun applyOperations(
        project: Project,
        userPrompt: String?,
        operations: List<EditOperation>,
        source: TransactionSource,
        assistantNote: String? = null,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(aiStage = AgentStage.APPLYING, aiStatus = "Applying edits") }
            val edit = container.editEngine.applyTransaction(project, userPrompt, operations, source)
            if (!edit.outcome.success) {
                _state.update {
                    it.copy(
                        aiStage = AgentStage.IDLE,
                        aiStatus = "",
                        message = null,
                        aiSummary = assistantNote ?: edit.outcome.summary,
                        aiWarnings = edit.outcome.errors,
                    )
                }
                return@launch
            }
            persist(edit.project)
            _state.update {
                it.copy(
                    project = edit.project,
                    aiStage = AgentStage.IDLE,
                    aiStatus = "",
                    aiSummary = assistantNote ?: edit.outcome.summary,
                    aiWarnings = edit.outcome.warnings,
                )
            }
            publishDerived(edit.project)
        }
    }

    // -----------------------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------------------

    fun setExportPreset(preset: ExportPreset) {
        val project = _state.value.project ?: return
        val settings = project.exportSettings.copy(
            preset = preset,
            width = preset.width,
            height = preset.height,
        )
        val updated = project.copy(exportSettings = settings)
        _state.update { it.copy(exportPreset = preset, project = updated) }
        viewModelScope.launch { persist(updated) }
    }

    /**
     * Plans and renders the current timeline.
     *
     * The export always reads the original media, and anything the engine cannot
     * bake in is reported to the user afterwards instead of being dropped
     * silently.
     */
    fun startExport() {
        val project = _state.value.project ?: return
        if (_state.value.isExporting) return
        if (project.timeline.allClips().isEmpty()) {
            _state.update { it.copy(exportState = ExportState.Failed("Import a video first.")) }
            return
        }

        exportJob = viewModelScope.launch {
            _state.update { it.copy(exportState = ExportState.Preparing, unsupportedFeatures = emptyList()) }
            val capabilities = runCatching { container.videoEngine.capabilities() }.getOrNull()
            val exportSettings = project.exportSettings.copy(
                preset = _state.value.exportPreset,
                width = _state.value.exportPreset.width,
                height = _state.value.exportPreset.height,
            )
            val plan = ExportPlanBuilder.build(
                project = project,
                settings = exportSettings,
                bakedEffects = capabilities?.bakedEffects ?: emptySet(),
            )
            if (plan.isEmpty) {
                _state.update { it.copy(exportState = ExportState.Failed("There is nothing to export.")) }
                return@launch
            }

            val directory = File(
                container.appContext.getExternalFilesDir(null) ?: container.appContext.filesDir,
                "exports",
            ).apply { mkdirs() }
            val output = File(directory, "Helium-${container.time.nowEpochMs()}.mp4")

            val outcome = try {
                container.videoEngine.export(ExportRequest(plan, output)) { progress, stage ->
                    _state.update {
                        it.copy(exportState = ExportState.Running(progress, stage), unsupportedFeatures = plan.unsupportedFeatures)
                    }
                }
            } catch (cancelled: CancellationException) {
                output.delete()
                _state.update { it.copy(exportState = ExportState.Cancelled) }
                throw cancelled
            } catch (failure: Throwable) {
                container.log.e(TAG, "Export failed: ${failure::class.simpleName}")
                output.delete()
                _state.update {
                    it.copy(
                        exportState = ExportState.Failed(
                            failure.message ?: "The export failed. Your project is unchanged.",
                        ),
                    )
                }
                return@launch
            }

            when (outcome) {
                is ExportOutcome.Success -> {
                    _state.update {
                        it.copy(
                            unsupportedFeatures = outcome.skippedFeatures,
                            lastExportPath = outcome.outputPath,
                        )
                    }
                    // Publishing to the shared Movies collection makes the file
                    // visible in the gallery and shareable from any app.
                    val published = ExportPublisher.publishToGallery(
                        context = container.appContext,
                        file = File(outcome.outputPath),
                    )
                    _state.update {
                        it.copy(
                            exportState = ExportState.Completed(
                                outputPath = published?.toString() ?: outcome.outputPath,
                                sizeBytes = outcome.sizeBytes,
                                durationMs = outcome.durationMs,
                                width = outcome.width,
                                height = outcome.height,
                            ),
                            lastExportUri = published?.toString(),
                            message = null,
                        )
                    }
                }
                is ExportOutcome.Failure ->
                    _state.update { it.copy(exportState = ExportState.Failed(outcome.message, outcome.recoverable)) }

                ExportOutcome.Cancelled ->
                    _state.update { it.copy(exportState = ExportState.Cancelled) }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(exportState = ExportState.Cancelled) }
    }

    fun dismissExportState() = _state.update { it.copy(exportState = ExportState.Idle) }

    /** Opens the Android share sheet for the most recent export. */
    fun shareLastExport() {
        val uri = _state.value.lastExportUri
            ?: _state.value.lastExportPath?.let { path ->
                ExportPublisher.fileProviderUri(container.appContext, File(path))?.toString()
            }
        if (uri == null) {
            _state.update { it.copy(message = "Export the video first, then share it.") }
            return
        }
        val intent = ExportPublisher.shareIntent(container.appContext, Uri.parse(uri))
        runCatching { container.appContext.startActivity(intent) }
            .onFailure { _state.update { state -> state.copy(message = "No app can open this video.") } }
    }

    /** Deletes Helium's derived cache for this project. Never touches user media. */
    fun deleteProjectCache() {
        val project = _state.value.project ?: return
        viewModelScope.launch {
            project.sources.forEach { source ->
                container.semanticIndexer.clearCache(source)
                container.semanticMemoryStore.deleteForSource(source.id)
            }
            memories.clear()
            _state.update { it.copy(message = "Analysis cache cleared. Your videos were not touched.") }
            publishDerived(project)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // The editor keeps working without the network; but an in-flight render
        // must not keep an orphaned encoder alive after the screen goes away.
        exportJob?.cancel()
        agentJob?.cancel()
        indexingJobs.values.forEach { it.cancel() }
    }

    private companion object {
        const val TAG = "EditorViewModel"
        const val MAX_PROMPT_CHARS = 2_000
        const val MIN_SPLIT_MS = 120L
    }
}
