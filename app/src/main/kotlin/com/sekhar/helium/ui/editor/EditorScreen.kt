package com.sekhar.helium.ui.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.sekhar.helium.core.model.ClipId
import com.sekhar.helium.core.model.ExportPreset
import com.sekhar.helium.core.model.ExportState
import com.sekhar.helium.core.model.Project
import com.sekhar.helium.core.model.Timeline
import com.sekhar.helium.core.model.formatDurationMs
import com.sekhar.helium.core.ui.components.HeliumChip
import com.sekhar.helium.core.ui.components.HeliumEmptyState
import com.sekhar.helium.core.ui.components.HeliumPrimaryButton
import com.sekhar.helium.core.ui.components.HeliumProgressBar
import com.sekhar.helium.core.ui.components.HeliumSecondaryButton
import com.sekhar.helium.core.ui.components.HeliumSectionHeader
import com.sekhar.helium.core.ui.theme.HeliumColors
import com.sekhar.helium.core.ui.theme.HeliumTextStyles
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * The editor.
 *
 * Layout mirrors how the work actually happens: the edited video at the top, the
 * timeline underneath it (video clips, captions and a real audio envelope derived
 * from the local analysis), and the natural-language field pinned at the bottom
 * where the thumbs are. All of it is drawn with Helium's own cherry-blossom
 * theme rather than copied from another editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onPromptChange: (String) -> Unit,
    onPrompt: () -> Unit,
    onCancelPrompt: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSeek: (Long) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onSelectClip: (ClipId?) -> Unit,
    onZoomChange: (Float) -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onExportPresetChange: (ExportPreset) -> Unit,
    onShareExport: () -> Unit,
    onDismissExportState: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val context = LocalContext.current
    var showExportSheet by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_ITEMS),
    ) { uris -> if (uris.isNotEmpty()) onImportUris(uris) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) onPromptChange(spoken)
        }
    }

    Scaffold(
        containerColor = HeliumColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.project?.name ?: "Editor",
                            style = MaterialTheme.typography.titleMedium,
                            color = HeliumColors.OnBackground,
                        )
                        Text(
                            text = "${formatDurationMs(state.durationMs)} · ${state.appliedTransactions.size} AI edit(s)",
                            style = HeliumTextStyles.Timestamp,
                            color = HeliumColors.OnSurfaceFaint,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Projects", color = HeliumColors.OnSurfaceMuted) }
                },
                actions = {
                    TextButton(onClick = onUndo, enabled = state.canUndo) {
                        Text("Undo", color = if (state.canUndo) HeliumColors.Blossom else HeliumColors.OnSurfaceFaint)
                    }
                    TextButton(onClick = onRedo, enabled = state.canRedo) {
                        Text("Redo", color = if (state.canRedo) HeliumColors.Blossom else HeliumColors.OnSurfaceFaint)
                    }
                    TextButton(onClick = { showExportSheet = true }) { Text("Export") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeliumColors.Background,
                    titleContentColor = HeliumColors.OnBackground,
                ),
            )
        },
    ) { padding ->
        if (state.missing) {
            HeliumEmptyState(
                title = "Project unavailable",
                body = "This project could not be opened. Its document may be damaged. " +
                    "Your original videos are untouched.",
                modifier = Modifier.fillMaxSize().padding(padding),
                action = { HeliumSecondaryButton(text = "Back to projects", onClick = onBack) },
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (!state.hasMedia) {
                ImportEmptyState(
                    modifier = Modifier.weight(1f),
                    onImport = {
                        importLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                )
                return@Column
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            ) {
                PreviewSurface(state = state, onPositionChanged = onPositionChanged)
                PlaybackControls(
                    state = state,
                    onTogglePlay = onTogglePlay,
                    onSeek = onSeek,
                    onSplitAtPlayhead = onSplitAtPlayhead,
                    onImport = {
                        importLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                )
                AnalysisStatus(state = state)
                TimelineView(
                    state = state,
                    onSeek = onSeek,
                    onSelectClip = onSelectClip,
                    onZoomChange = onZoomChange,
                )
                EditHistoryStrip(state = state)
                state.aiSummary?.let { summary ->
                    NoticeCard(
                        title = "What changed",
                        body = summary,
                        accent = HeliumColors.Blossom,
                        onDismiss = onDismissMessage,
                    )
                }
                if (state.aiWarnings.isNotEmpty()) {
                    NoticeCard(
                        title = "Could not use every suggestion",
                        body = state.aiWarnings.take(4).joinToString("\n"),
                        accent = HeliumColors.Amber,
                        onDismiss = onDismissMessage,
                    )
                }
                state.message?.let { message ->
                    NoticeCard(
                        title = "Helium",
                        body = message,
                        accent = HeliumColors.Plum,
                        onDismiss = onDismissMessage,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            AiPromptPanel(
                state = state,
                onPromptChange = onPromptChange,
                onPrompt = onPrompt,
                onCancelPrompt = onCancelPrompt,
                onDictate = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the edit")
                    }
                    runCatching { speechLauncher.launch(intent) }
                },
            )
        }
    }

    if (showExportSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showExportSheet = false
                onDismissExportState()
            },
            sheetState = sheetState,
            containerColor = HeliumColors.Surface,
        ) {
            ExportSheet(
                state = state,
                onPresetChange = onExportPresetChange,
                onStart = onExport,
                onCancel = onCancelExport,
                onShare = onShareExport,
            )
        }
    }
}

// ---------------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------------

@Composable
private fun ImportEmptyState(modifier: Modifier, onImport: () -> Unit) {
    HeliumEmptyState(
        title = "Add your footage",
        body = "Helium analyses video on your phone and edits it from plain English. " +
            "Import one or several clips to begin.",
        modifier = modifier,
        action = { HeliumPrimaryButton(text = "Import video", onClick = onImport) },
    )
}

// ---------------------------------------------------------------------------------
// Preview + transport
// ---------------------------------------------------------------------------------

/**
 * ExoPlayer rendering the *edited* playlist: one clipped item per timeline clip.
 *
 * The player is owned by the composable and released with it; the ViewModel only
 * holds the playhead, so a configuration change rebuilds the player without
 * losing the edit state.
 */
@Composable
private fun PreviewSurface(
    state: EditorUiState,
    onPositionChanged: (Long) -> Unit,
) {
    val context = LocalContext.current
    val segments = state.previewSegments
    val project = state.project

    val player = remember {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val playlistKey = remember(segments) {
        segments.joinToString("|") { "${it.clipId.value}:${it.sourceStartMs}-${it.sourceEndMsExclusive}" }
    }

    LaunchedEffect(playlistKey, state.settings.useProxyForPreview) {
        if (project == null || segments.isEmpty()) return@LaunchedEffect
        player.setMediaItems(buildPreviewItems(project, segments, state.settings.useProxyForPreview))
        player.prepare()
        TimelinePreviewModel.locate(segments, state.playheadMs)?.let { location ->
            player.seekTo(location.segmentIndex, location.sourceOffsetMs)
        }
    }

    LaunchedEffect(state.isPlaying) {
        player.playWhenReady = state.isPlaying
    }

    // While playing, the player is the clock: its position is mapped back onto the
    // timeline so the playhead and the scrubber follow the real playback.
    LaunchedEffect(state.isPlaying, playlistKey) {
        if (!state.isPlaying || segments.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val index = player.currentMediaItemIndex.coerceIn(0, segments.lastIndex)
            val offset = max(0L, player.currentPosition)
            val before = segments.take(index).sumOf { it.timelineDurationMs }
            onPositionChanged(before + offset)
            if (player.playbackState == Player.STATE_ENDED) {
                onPositionChanged(segments.sumOf { it.timelineDurationMs })
                break
            }
            delay(POSITION_POLL_MS)
        }
    }

    // Scrubbing only drives the player while paused, so polling never fights the user.
    LaunchedEffect(state.playheadMs, state.isPlaying, playlistKey) {
        if (state.isPlaying || segments.isEmpty()) return@LaunchedEffect
        val location = TimelinePreviewModel.locate(segments, state.playheadMs) ?: return@LaunchedEffect
        if (player.currentMediaItemIndex != location.segmentIndex) {
            player.seekTo(location.segmentIndex, location.sourceOffsetMs)
        } else {
            player.seekTo(location.sourceOffsetMs)
        }
    }

    val aspect = remember(project) {
        project?.let(::previewAspect) ?: DEFAULT_PREVIEW_ASPECT
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    keepScreenOn = true
                }
            },
            update = { view -> view.player = player },
        )

        CaptionOverlay(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (state.aiRunning) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeliumChip(
                    text = state.aiStatus.ifBlank { state.aiStage.name.lowercase() },
                    accent = HeliumColors.Blossom,
                    background = HeliumColors.Scrim,
                )
            }
        }
    }
}

/** Aspect ratio of the first clip's source, clamped so the preview stays usable. */
private fun previewAspect(project: Project): Float {
    val clip = project.timeline.allClips().minByOrNull { it.timelineStartMs } ?: return DEFAULT_PREVIEW_ASPECT
    val source = project.source(clip.sourceId) ?: return DEFAULT_PREVIEW_ASPECT
    val ratio = source.metadata.aspectRatio
    return if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.5f, 2.2f) else DEFAULT_PREVIEW_ASPECT
}

/**
 * Used before the first clip is known (and for damaged sources). Short-form is
 * the product's primary format, so the empty preview is already vertical.
 */
private const val DEFAULT_PREVIEW_ASPECT: Float = 9f / 16f

/**
 * Builds the preview playlist from the timeline.
 *
 * Each clip becomes a `MediaItem` clipped to the exact source range, so the
 * preview plays the current edit rather than the raw files. Preview may use the
 * low-resolution proxy for responsiveness; export never does.
 */
private fun buildPreviewItems(
    project: Project,
    segments: List<PreviewSegment>,
    useProxy: Boolean,
): List<MediaItem> = segments.mapNotNull { segment ->
    val source = project.source(segment.sourceId) ?: return@mapNotNull null
    // Local copy so the null-check contract smart-casts for File(..).
    val proxyPath = source.proxyPath
    val uri = if (useProxy && !proxyPath.isNullOrBlank()) {
        Uri.fromFile(java.io.File(proxyPath))
    } else {
        Uri.parse(source.uri)
    }
    MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(segment.sourceStartMs)
                .setEndPositionMs(segment.sourceEndMsExclusive)
                .build(),
        )
        .build()
}

@Composable
private fun PlaybackControls(
    state: EditorUiState,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(HeliumColors.Blossom)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.isPlaying) "❚❚" else "▶",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = formatDurationMs(state.playheadMs),
                style = HeliumTextStyles.Timestamp,
                color = HeliumColors.OnBackground,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSplitAtPlayhead) { Text("Split", color = HeliumColors.OnSurfaceMuted) }
            TextButton(onClick = onImport) { Text("Import", color = HeliumColors.OnSurfaceMuted) }
        }

        Spacer(Modifier.height(6.dp))
        ScrubBar(
            playheadMs = state.playheadMs,
            durationMs = state.durationMs,
            onScrub = onSeek,
        )
    }
}

/** Thin scrubber above the timeline for precise, immediate seeking. */
@Composable
private fun ScrubBar(
    playheadMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
) {
    val trackHeight = 22.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onScrub((durationMs * fraction).toLong())
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            drawLine(
                color = HeliumColors.SurfaceHighest,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 6f,
            )
            if (durationMs > 0L) {
                val x = size.width * (playheadMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                drawLine(
                    color = HeliumColors.Blossom,
                    start = Offset(0f, centerY),
                    end = Offset(x, centerY),
                    strokeWidth = 6f,
                )
                drawCircle(color = HeliumColors.BlossomBright, radius = 9f, center = Offset(x, centerY))
            }
        }
    }
}

/**
 * Live caption preview: renders the text items that are active at the playhead,
 * with the current spoken word highlighted when word timings exist.
 */
@Composable
private fun CaptionOverlay(state: EditorUiState, modifier: Modifier = Modifier) {
    val project = state.project ?: return
    val active = remember(project, state.playheadMs) {
        project.timeline.allTextItems().filter { it.range.contains(state.playheadMs) }
    }
    if (active.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        active.take(2).forEach { item ->
            val words = if (item.words.isEmpty()) {
                item.text
            } else {
                val index = item.activeWordIndex(state.playheadMs)
                item.words.joinToString(" ") { word ->
                    if (word === item.words.getOrNull(index)) word.text.uppercase() else word.text
                }
            }
            Surface(
                color = Color(0x99000000),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Text(
                    text = words,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.words.isNotEmpty() && item.activeWordIndex(state.playheadMs) >= 0) {
                        HeliumColors.Blossom
                    } else {
                        Color.White
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Analysis status
// ---------------------------------------------------------------------------------

@Composable
private fun AnalysisStatus(state: EditorUiState) {
    val indexing = state.indexing
    val project = state.project ?: return
    val total = project.usableSources.size
    val done = total - indexing.size
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        HeliumSectionHeader(text = if (indexing.isEmpty()) "Analysed on device" else "Analysing on device")
        Spacer(Modifier.height(4.dp))
        if (indexing.isEmpty()) {
            val facts = buildList {
                add("$done of $total clip(s) indexed")
                add(if (state.settings.cloudAnalysisMode.name == "MINIMIZE") "cloud analysis minimised" else "cloud analysis ${state.settings.cloudAnalysisMode.name.lowercase()}")
                add(if (state.waveform.isEmpty()) "waveform pending" else "waveform ready")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                facts.take(2).forEach { HeliumChip(text = it, accent = HeliumColors.Sage) }
            }
        } else {
            indexing.forEach { (sourceId, progress) ->
                val source = project.source(sourceId)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = source?.displayName ?: sourceId.value.take(6),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeliumColors.OnBackground,
                        modifier = Modifier.width(120.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        HeliumProgressBar(progress = progress.progress)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = progress.message,
                            style = HeliumTextStyles.Timestamp,
                            color = HeliumColors.OnSurfaceFaint,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Timeline
// ---------------------------------------------------------------------------------

private val TIMELINE_HEIGHT = 132.dp
private val RULER_HEIGHT = 18.dp
private val VIDEO_ROW_HEIGHT = 46.dp
private val TEXT_ROW_HEIGHT = 22.dp
private val AUDIO_ROW_HEIGHT = 30.dp

@Composable
private fun TimelineView(
    state: EditorUiState,
    onSeek: (Long) -> Unit,
    onSelectClip: (ClipId?) -> Unit,
    onZoomChange: (Float) -> Unit,
) {
    val project = state.project ?: return
    val timeline = project.timeline
    val duration = max(1L, timeline.durationMs)
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val viewportWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    // "Fit" is the zoom-1 baseline: the whole timeline exactly fills the viewport.
    val fitPxPerMs = viewportWidthPx / duration.toFloat()
    val pxPerMs = (fitPxPerMs * state.zoom).coerceAtLeast(MIN_PX_PER_MS)
    val contentWidthDp = with(density) { (duration.toFloat() * pxPerMs).toDp() }
    val playheadXDp = with(density) { (state.playheadMs.toFloat() * pxPerMs).toDp() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeliumSectionHeader(text = "Timeline", modifier = Modifier.weight(1f))
            HeliumChip(
                text = "${"%.1f".format(state.zoom)}× zoom",
                accent = HeliumColors.Plum,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIMELINE_HEIGHT)
                .clip(RoundedCornerShape(16.dp))
                .background(HeliumColors.Surface)
                // Pinch to zoom the timeline; two fingers never fight the scrubber.
                .pointerInput(state.zoom) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        onZoomChange(state.zoom * zoomChange)
                    }
                },
        ) {
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(scrollState)) {
                Box(
                    modifier = Modifier
                        .width(contentWidthDp)
                        .height(TIMELINE_HEIGHT)
                        .pointerInput(duration, pxPerMs) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / pxPerMs).toLong())
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawTimeline(
                            timeline = timeline,
                            waveform = state.waveform,
                            pxPerMs = pxPerMs,
                            rulerHeightPx = RULER_HEIGHT.toPx(),
                            videoRowTopPx = RULER_HEIGHT.toPx() + 4f,
                            videoRowHeightPx = VIDEO_ROW_HEIGHT.toPx(),
                            textRowTopPx = RULER_HEIGHT.toPx() + VIDEO_ROW_HEIGHT.toPx() + 8f,
                            textRowHeightPx = TEXT_ROW_HEIGHT.toPx(),
                            audioRowTopPx = RULER_HEIGHT.toPx() + VIDEO_ROW_HEIGHT.toPx() +
                                TEXT_ROW_HEIGHT.toPx() + 12f,
                            audioRowHeightPx = AUDIO_ROW_HEIGHT.toPx(),
                            selectedClipId = state.selectedClipId,
                        )
                    }

                    // Windowing: only clips overlapping the visible window get a label,
                    // which keeps a long timeline cheap to render.
                    val visibleStartPx = scrollState.value.toFloat()
                    val visibleEndPx = visibleStartPx + viewportWidthPx
                    val clipLabelHeightDp = RULER_HEIGHT + 6.dp

                    timeline.allClips()
                        .sortedBy { it.timelineStartMs }
                        .forEachIndexed { index, clip ->
                            val startPx = clip.timelineStartMs * pxPerMs
                            val endPx = clip.timelineEndMs * pxPerMs
                            if (endPx < visibleStartPx || startPx > visibleEndPx) {
                                return@forEachIndexed
                            }
                            val leftDp = with(density) { startPx.toDp() }
                            Text(
                                text = buildString {
                                    append("#${index + 1}")
                                    if (clip.speed != 1f) append(" ${clip.speed}×")
                                    if (clip.freeze != null) append(" ❄")
                                    if (clip.effects.isNotEmpty()) append(" ✦")
                                },
                                modifier = Modifier
                                    .offset(x = leftDp + 5.dp, y = clipLabelHeightDp)
                                    .clickable { onSelectClip(clip.id) },
                                style = HeliumTextStyles.Timestamp,
                                color = HeliumColors.OnBackground,
                            )
                        }

                    // Playhead.
                    Box(
                        modifier = Modifier
                            .offset(x = playheadXDp)
                            .width(2.dp)
                            .height(TIMELINE_HEIGHT)
                            .background(HeliumColors.Playhead),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Tap to seek · pinch to zoom · ${timeline.allClips().size} clip(s) · " +
                "${timeline.allTextItems().size} caption(s)",
            style = HeliumTextStyles.Timestamp,
            color = HeliumColors.OnSurfaceFaint,
        )
    }
}

/**
 * Draws the ruler, clip blocks, caption lane, audio envelope and playhead.
 *
 * A single `Canvas` keeps the timeline a constant-cost draw regardless of clip
 * count; labels are composables rendered only for the visible window.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTimeline(
    timeline: Timeline,
    waveform: List<Float>,
    pxPerMs: Float,
    rulerHeightPx: Float,
    videoRowTopPx: Float,
    videoRowHeightPx: Float,
    textRowTopPx: Float,
    textRowHeightPx: Float,
    audioRowTopPx: Float,
    audioRowHeightPx: Float,
    selectedClipId: ClipId?,
) {
    val totalWidth = timeline.durationMs.toFloat() * pxPerMs

    // Ruler.
    val stepMs = rulerStepMs(pxPerMs)
    var tick = 0L
    while (tick <= timeline.durationMs) {
        val x = tick.toFloat() * pxPerMs
        val major = tick % (stepMs * 5) == 0L
        drawLine(
            color = if (major) HeliumColors.Outline else HeliumColors.OutlineFaint,
            start = Offset(x, rulerHeightPx - if (major) 10f else 5f),
            end = Offset(x, rulerHeightPx),
            strokeWidth = 1f,
        )
        tick += stepMs
    }
    drawLine(
        color = HeliumColors.OutlineFaint,
        start = Offset(0f, rulerHeightPx),
        end = Offset(totalWidth, rulerHeightPx),
        strokeWidth = 1f,
    )

    // Video clips.
    timeline.allClips().sortedBy { it.timelineStartMs }.forEach { clip ->
        val left = clip.timelineStartMs.toFloat() * pxPerMs
        val width = max(2f, clip.timelineEndMs.toFloat() * pxPerMs - left)
        val selected = clip.id == selectedClipId
        drawRoundRect(
            color = if (selected) HeliumColors.TrackVideoSelected else HeliumColors.TrackVideo,
            topLeft = Offset(left, videoRowTopPx),
            size = Size(width, videoRowHeightPx),
            cornerRadius = CornerRadius(8f),
        )
        if (clip.speed != 1f) {
            drawRoundRect(
                color = HeliumColors.Amber,
                topLeft = Offset(left, videoRowTopPx + videoRowHeightPx - 4f),
                size = Size(width, 3f),
                cornerRadius = CornerRadius(2f),
            )
        }
        if (clip.freeze != null) {
            drawCircle(
                color = HeliumColors.BlossomBright,
                radius = 3.5f,
                center = Offset(left + 8f, videoRowTopPx + 8f),
            )
        }
        if (clip.effects.isNotEmpty()) {
            drawRoundRect(
                color = HeliumColors.Plum,
                topLeft = Offset(left, videoRowTopPx),
                size = Size(min(width, 3f), videoRowHeightPx),
                cornerRadius = CornerRadius(2f),
            )
        }
    }

    // Caption lane.
    timeline.allTextItems().forEach { item ->
        val left = item.range.startMs.toFloat() * pxPerMs
        val width = max(3f, item.range.durationMs.toFloat() * pxPerMs)
        drawRoundRect(
            color = HeliumColors.TrackText,
            topLeft = Offset(left, textRowTopPx),
            size = Size(width, textRowHeightPx),
            cornerRadius = CornerRadius(5f),
        )
    }

    // Audio envelope, drawn from the locally analysed RMS curve.
    drawRoundRect(
        color = HeliumColors.TrackAudio,
        topLeft = Offset(0f, audioRowTopPx),
        size = Size(max(totalWidth, 1f), audioRowHeightPx),
        cornerRadius = CornerRadius(6f),
    )
    if (waveform.isNotEmpty()) {
        val centerY = audioRowTopPx + audioRowHeightPx / 2f
        val maxHalf = audioRowHeightPx / 2f - 2f
        waveform.forEachIndexed { index, amplitude ->
            val x = (index.toFloat() / waveform.size.toFloat()) * totalWidth
            val half = max(1f, amplitude * maxHalf)
            drawLine(
                color = HeliumColors.Sage,
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = max(1f, totalWidth / waveform.size.toFloat() - 0.5f),
            )
        }
    }

}

/** Sensible ruler step for the current scale, so labels never overlap. */
private fun rulerStepMs(pxPerMs: Float): Long {
    val targetPx = 56f
    val candidates = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
    return candidates.firstOrNull { it * pxPerMs >= targetPx } ?: candidates.last()
}

@Composable
private fun EditHistoryStrip(state: EditorUiState) {
    val transactions = state.appliedTransactions
    HeliumSectionHeader(
        text = if (transactions.isEmpty()) "No AI edits yet" else "Applied edits",
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (transactions.isEmpty()) {
        Text(
            text = "Describe what you want in the field below. Every AI edit can be undone.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = HeliumColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(transactions, key = { it.id }) { transaction ->
            Surface(
                color = HeliumColors.SurfaceElevated,
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = transaction.userPrompt?.take(46) ?: "Manual edit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeliumColors.OnBackground,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = transaction.summary.take(70),
                        style = HeliumTextStyles.Timestamp,
                        color = HeliumColors.Blossom,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    accent: Color,
    onDismiss: () -> Unit,
) {
    Surface(
        color = HeliumColors.SurfaceElevated,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title.uppercase(),
                    style = HeliumTextStyles.SectionLabel,
                    color = HeliumColors.OnSurfaceFaint,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Text("✕", color = HeliumColors.OnSurfaceFaint)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = HeliumColors.OnBackground,
            )
        }
    }
}

// ---------------------------------------------------------------------------------
// AI prompt field
// ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiPromptPanel(
    state: EditorUiState,
    onPromptChange: (String) -> Unit,
    onPrompt: () -> Unit,
    onCancelPrompt: () -> Unit,
    onDictate: () -> Unit,
) {
    Surface(
        color = HeliumColors.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            HeliumSectionHeader(text = "AI edit")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 132.dp),
                placeholder = {
                    Text(
                        text = "Tell AI how to edit this video…",
                        color = HeliumColors.OnSurfaceFaint,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onDictate) { Text("🎙", color = HeliumColors.Plum) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPrompt() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HeliumColors.SurfaceElevated,
                    unfocusedContainerColor = HeliumColors.SurfaceElevated,
                    focusedBorderColor = HeliumColors.Blossom,
                    unfocusedBorderColor = HeliumColors.Outline,
                    focusedTextColor = HeliumColors.OnBackground,
                    unfocusedTextColor = HeliumColors.OnBackground,
                    cursorColor = HeliumColors.Blossom,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Prompts.quickActions.take(2).forEach { example ->
                    Surface(
                        color = HeliumColors.SurfaceHighest,
                        shape = CircleShape,
                        modifier = Modifier.clickable { onPromptChange(example) },
                    ) {
                        Text(
                            text = example.take(28),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = HeliumTextStyles.Timestamp,
                            color = HeliumColors.OnSurfaceMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.aiRunning) {
                HeliumProgressBar(progress = 0.5f)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.aiStatus.ifBlank { "Working…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeliumColors.Blossom,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelPrompt) { Text("Stop", color = HeliumColors.OnSurfaceMuted) }
                }
            } else {
                HeliumPrimaryButton(
                    text = "Apply AI edit",
                    onClick = onPrompt,
                    enabled = state.prompt.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Example instructions shown as one-tap chips. */
internal object Prompts {
    val quickActions = listOf(
        "Turn this into a 25-second Reel. Remove boring pauses and add captions.",
        "Start when the most interesting thing happens.",
        "Make this 9:16 and keep me centred.",
        "Find the pauses and remove them.",
        "Add captions and highlight the current spoken word.",
        "Undo the last zoom.",
    )
}

// ---------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------

@Composable
private fun ExportSheet(
    state: EditorUiState,
    onPresetChange: (ExportPreset) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        HeliumSectionHeader(text = "Export")
        Text(
            text = "Rendered from your original files, never from the analysis proxy.",
            style = MaterialTheme.typography.bodyMedium,
            color = HeliumColors.OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))

        ExportPreset.entries.forEach { preset ->
            val selected = preset == state.exportPreset
            Surface(
                color = if (selected) HeliumColors.SurfaceHighest else HeliumColors.SurfaceElevated,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) HeliumColors.Blossom else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable { onPresetChange(preset) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HeliumColors.OnBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "H.264 · AAC",
                        style = HeliumTextStyles.Timestamp,
                        color = HeliumColors.OnSurfaceFaint,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.unsupportedFeatures.isNotEmpty()) {
            NoticeCard(
                title = "Preview-only in this build",
                body = state.unsupportedFeatures.joinToString(", ") +
                    " — these stay in your project and in the preview but are not burned into this render yet.",
                accent = HeliumColors.Amber,
                onDismiss = {},
            )
        }

        when (val export = state.exportState) {
            is ExportState.Running -> {
                HeliumProgressBar(progress = export.progress)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${export.stage} · ${(export.progress * 100).toInt()}%",
                    style = HeliumTextStyles.Timestamp,
                    color = HeliumColors.OnSurfaceMuted,
                )
                Spacer(Modifier.height(10.dp))
                HeliumSecondaryButton(text = "Cancel export", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }

            is ExportState.Preparing -> {
                HeliumProgressBar(progress = 0.05f)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Preparing the render…",
                    style = HeliumTextStyles.Timestamp,
                    color = HeliumColors.OnSurfaceMuted,
                )
            }

            is ExportState.Completed -> {
                HeliumChip(text = "Saved to Movies/Helium · ${export.width}×${export.height}", accent = HeliumColors.Success)
                Spacer(Modifier.height(10.dp))
                HeliumPrimaryButton(text = "Share video", onClick = onShare, modifier = Modifier.fillMaxWidth())
            }

            is ExportState.Failed -> {
                HeliumChip(text = export.message, accent = HeliumColors.Error)
                Spacer(Modifier.height(10.dp))
                HeliumPrimaryButton(text = "Try again", onClick = onStart, modifier = Modifier.fillMaxWidth())
            }

            else -> {
                HeliumPrimaryButton(
                    text = "Export ${state.exportPreset.displayName}",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private const val MAX_IMPORT_ITEMS = 10
private const val POSITION_POLL_MS = 120L

/**
 * Floor for the timeline scale (`px per millisecond`) so an hour-long video at
 * high zoom-out still produces a finite, scrollable layout.
 */
private const val MIN_PX_PER_MS: Float = 0.000001f
