package com.sekhar.helium.ai.tools

import kotlinx.serialization.json.JsonObject

/**
 * Every tool the model may call.
 *
 * The catalog is the single source of truth: it is what gets advertised to the
 * provider, what the argument parser validates against, and what
 * `AI_TOOLS.md` documents. Adding a tool here without a parser branch is caught
 * by `ToolCatalogTest`.
 *
 * The interfaces are deliberately shaped so the same catalog could be exposed as
 * a real MCP server later without touching the editor: a tool is a name, a JSON
 * Schema and a handler, which is exactly MCP's `tools/list` + `tools/call` shape.
 */
object ToolCatalog {

    private val videoId = SchemaProp(
        name = "videoId",
        schema = stringSchema("Id of the source video, as reported by get_project_state."),
    )

    private val startMs = SchemaProp(
        name = "startMs",
        schema = integerSchema("Start of the range in SOURCE milliseconds, inclusive.", minimum = 0.0),
    )

    private val endMs = SchemaProp(
        name = "endMs",
        schema = integerSchema("End of the range in SOURCE milliseconds, exclusive.", minimum = 0.0),
    )

    // -----------------------------------------------------------------------------
    // Inspection
    // -----------------------------------------------------------------------------

    private val inspection: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = ToolNames.GET_PROJECT_STATE,
            description = "Current project state: sources, aspect ratio, duration, clips per track, " +
                "text items, applied edit count and the undo cursor. Call this first to learn the " +
                "exact clip, track and source ids before proposing edits.",
            parameters = jsonObject(),
            category = ToolCategory.PROJECT,
        ),
        ToolDefinition(
            name = ToolNames.GET_VIDEO_OVERVIEW,
            description = "Level 1 overview of a source: duration, resolution, orientation, audio " +
                "presence, scenes/chapters, ranked major events, and a short speech summary. Always " +
                "start video reasoning here rather than requesting frames.",
            parameters = jsonObject(videoId),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.SEARCH_VIDEO,
            description = "Search the local semantic index for a moment described in words, e.g. " +
                "'phone falls on the floor' or 'laughing'. Returns ranked candidate ranges with the " +
                "evidence that produced them (speech, on-screen text, audio impact, motion).",
            parameters = jsonObject(
                videoId,
                SchemaProp("semanticQuery", stringSchema("Free-text description of the moment to find.")),
                SchemaProp(
                    "limit",
                    integerSchema("Maximum number of candidates to return.", minimum = 1.0, maximum = 20.0),
                    required = false,
                ),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_SCENE,
            description = "Full detail for one scene: exact range, transcript, on-screen text, motion " +
                "and change scores, and the ids of its keyframes and storyboard strip.",
            parameters = jsonObject(
                SchemaProp("sceneId", stringSchema("Scene id from get_video_overview.")),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_TRANSCRIPT,
            description = "Transcript segments overlapping a source range, with word timings when " +
                "available. Use a narrow range: sending the whole transcript wastes tokens.",
            parameters = jsonObject(videoId, startMs, endMs),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.FIND_SPOKEN_TEXT,
            description = "Find where a phrase is spoken. Returns exact source ranges. Prefer this over " +
                "get_transcript when you know what was said, e.g. locating \"here we go\".",
            parameters = jsonObject(
                videoId,
                SchemaProp("query", stringSchema("Phrase to locate in the transcript.")),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.FIND_VISIBLE_TEXT,
            description = "Find where text is visible on screen (OCR). Use for commands about titles, " +
                "captions burned into the footage, prices or phone numbers.",
            parameters = jsonObject(
                videoId,
                SchemaProp("query", stringSchema("Text to locate on screen.")),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_AUDIO_EVENTS,
            description = "Audio events in a range: silence, speech start/end, music regions, impacts, " +
                "peaks and loudness changes. Cheap, text-only evidence for pace and pause decisions.",
            parameters = jsonObject(videoId, startMs, endMs),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_VISUAL_EVENTS,
            description = "Visual events in a range: scene changes, shot boundaries, motion onsets, " +
                "static holds, on-screen text appearing/disappearing and brightness changes.",
            parameters = jsonObject(videoId, startMs, endMs),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.INSPECT_SEGMENT,
            description = "Level 3 evidence: a compact temporal strip of a narrow range, sampled by " +
                "information change rather than fixed frame rate. Use when the overview and search " +
                "results are not confident enough to choose an exact timestamp. Keep ranges short.",
            parameters = jsonObject(
                videoId,
                startMs,
                endMs,
                SchemaProp(
                    "sampleCount",
                    integerSchema("How many temporal states to include.", minimum = 2.0, maximum = 16.0),
                ),
                SchemaProp(
                    "quality",
                    stringSchema("Image quality to fetch.", enum = listOf("proxy", "original")),
                    required = false,
                ),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_FRAME,
            description = "A single low-resolution proxy frame at an exact timestamp. Cheaper than " +
                "inspect_segment when you only need to confirm one moment.",
            parameters = jsonObject(
                videoId,
                SchemaProp("timestampMs", integerSchema("Exact source timestamp in milliseconds.", minimum = 0.0)),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_ORIGINAL_FRAME,
            description = "Level 4 evidence: a frame from the ORIGINAL full-resolution media, optionally " +
                "cropped. This is the most expensive and most privacy-sensitive call — use it only when " +
                "proxy evidence is genuinely insufficient, and tell the user it happened.",
            parameters = jsonObject(
                videoId,
                SchemaProp("timestampMs", integerSchema("Exact source timestamp in milliseconds.", minimum = 0.0)),
                SchemaProp(
                    "crop",
                    objectSchema(
                        "Optional normalised crop, 0..1, origin top-left.",
                        SchemaProp("left", numberSchema("Left edge.", 0.0, 1.0)),
                        SchemaProp("top", numberSchema("Top edge.", 0.0, 1.0)),
                        SchemaProp("right", numberSchema("Right edge.", 0.0, 1.0)),
                        SchemaProp("bottom", numberSchema("Bottom edge.", 0.0, 1.0)),
                    ),
                    required = false,
                ),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.GET_TEMPORAL_STRIP,
            description = "The cached storyboard/contact sheet for a scene: one image containing " +
                "several temporal states with per-cell timestamp labels. The most token-efficient way " +
                "to reason about motion.",
            parameters = jsonObject(
                SchemaProp("sceneId", stringSchema("Scene id from get_video_overview.")),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.FIND_HIGH_MOTION_SEGMENTS,
            description = "Ranges ranked by visual motion. Useful for action montages, 'the exciting " +
                "bits', or avoiding static filler.",
            parameters = jsonObject(
                videoId,
                SchemaProp(
                    "limit",
                    integerSchema("Maximum number of segments.", minimum = 1.0, maximum = 30.0),
                    required = false,
                ),
            ),
            category = ToolCategory.INSPECTION,
        ),
        ToolDefinition(
            name = ToolNames.FIND_SILENCE,
            description = "Silent regions longer than a threshold, with exact ranges. This is the " +
                "primary input for 'remove the pauses'. Deliberately distinguishes long dead air from " +
                "short natural gaps so dramatic beats can be preserved.",
            parameters = jsonObject(
                videoId,
                SchemaProp(
                    "minDurationMs",
                    integerSchema("Ignore silences shorter than this.", minimum = 100.0),
                    required = false,
                ),
            ),
            category = ToolCategory.INSPECTION,
        ),
    )

    // -----------------------------------------------------------------------------
    // Editing
    // -----------------------------------------------------------------------------

    private val editing: List<ToolDefinition> = listOf(
        ToolDefinition(
            ToolNames.SPLIT_CLIP,
            "Split a clip in two at a source timestamp.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id from get_project_state.")),
                SchemaProp("atSourceMs", integerSchema("Split point in source milliseconds.", minimum = 0.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.TRIM_CLIP,
            "Re-trim a clip to play a different part of its source.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id from get_project_state.")),
                startMs,
                endMs,
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.REMOVE_RANGE,
            "Cut a range out of a source everywhere it appears and close the gap. This is the " +
                "operation behind 'remove the pauses' and 'cut everything before I say here we go'.",
            jsonObject(videoId, startMs, endMs),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.RESTORE_RANGE,
            "Bring back a source range that was previously removed.",
            jsonObject(videoId, startMs, endMs),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.MOVE_CLIP,
            "Move a clip to another video track and timeline position.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id.")),
                SchemaProp("toTrackId", stringSchema("Destination video track id.")),
                SchemaProp("newTimelineStartMs", integerSchema("Desired timeline start.", minimum = 0.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.DUPLICATE_CLIP,
            "Copy a clip to another timeline position.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id.")),
                SchemaProp("newTimelineStartMs", integerSchema("Where the copy should start.", minimum = 0.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.SET_SPEED,
            "Change playback speed over a source range. 1.25 means 25% faster.",
            jsonObject(
                videoId,
                startMs,
                endMs,
                SchemaProp("speed", numberSchema("New speed, 0.1 to 10.", 0.1, 10.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.SET_VOLUME,
            "Set original audio volume over a source range. 1.0 is unchanged, 0 mutes.",
            jsonObject(
                videoId,
                startMs,
                endMs,
                SchemaProp("volume", numberSchema("Linear gain, 0 to 4.", 0.0, 4.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.MUTE_RANGE,
            "Silence the original audio over a source range without removing the video.",
            jsonObject(videoId, startMs, endMs),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.SET_ASPECT_RATIO,
            "Set the project output aspect ratio.",
            jsonObject(
                SchemaProp(
                    "aspectRatio",
                    stringSchema("Target ratio.", enum = listOf("9:16", "16:9", "1:1", "4:5", "original")),
                ),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.CROP_CLIP,
            "Crop a clip to an explicit normalised rectangle.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id.")),
                SchemaProp("left", numberSchema("Left edge, 0..1.", 0.0, 1.0)),
                SchemaProp("top", numberSchema("Top edge, 0..1.", 0.0, 1.0)),
                SchemaProp("right", numberSchema("Right edge, 0..1.", 0.0, 1.0)),
                SchemaProp("bottom", numberSchema("Bottom edge, 0..1.", 0.0, 1.0)),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.REFRAME_CLIP,
            "Re-frame a clip to a target aspect while keeping a subject centred. Use focus points from " +
                "person/object evidence where available, otherwise leave them centred.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id.")),
                SchemaProp(
                    "targetAspect",
                    stringSchema("Target ratio.", enum = listOf("9:16", "16:9", "1:1", "4:5", "original")),
                ),
                SchemaProp("focusX", numberSchema("Subject centre X, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("focusY", numberSchema("Subject centre Y, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("zoomScale", numberSchema("Extra zoom, 1 or greater.", 1.0, 8.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_ZOOM,
            "Zoom towards a point over a source range. Use for emphasis such as 'zoom in when the " +
                "phone falls'.",
            jsonObject(
                videoId,
                startMs,
                endMs,
                SchemaProp("scale", numberSchema("Zoom factor, 1 to 8.", 1.0, 8.0)),
                SchemaProp("focusX", numberSchema("Focus X, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("focusY", numberSchema("Focus Y, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("easeInMs", integerSchema("Ease-in duration.", 0.0, 5000.0), required = false),
                SchemaProp("easeOutMs", integerSchema("Ease-out duration.", 0.0, 5000.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_TEXT,
            "Add free-standing text at an absolute timeline position (not source time).",
            jsonObject(
                SchemaProp("timelineStartMs", integerSchema("Timeline position.", minimum = 0.0)),
                SchemaProp("durationMs", integerSchema("How long the text is visible.", minimum = 100.0)),
                SchemaProp("text", stringSchema("The text to show.")),
                SchemaProp(
                    "preset",
                    stringSchema("Style preset.", enum = listOf("title", "reel_caption", "plain")),
                    required = false,
                ),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_CAPTION,
            "Add captions derived from the transcript. Cue ranges are SOURCE milliseconds so they stay " +
                "correct after other edits move the timeline. Word timings enable active-word highlighting.",
            jsonObject(
                videoId,
                SchemaProp(
                    "cues",
                    arraySchema(
                        "Caption cues in source time, in order.",
                        objectSchema(
                            "One cue.",
                            startMs,
                            endMs,
                            SchemaProp("text", stringSchema("Caption text.")),
                            SchemaProp(
                                "words",
                                arraySchema(
                                    "Optional word timings for karaoke highlighting.",
                                    objectSchema(
                                        "One word.",
                                        startMs,
                                        endMs,
                                        SchemaProp("text", stringSchema("Word.")),
                                    ),
                                ),
                                required = false,
                            ),
                        ),
                    ),
                ),
                SchemaProp(
                    "preset",
                    stringSchema("Caption preset.", enum = listOf("reel_caption", "title", "plain")),
                    required = false,
                ),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.REMOVE_CAPTION,
            "Remove caption/text items by id, by matching text, or all captions belonging to a source.",
            jsonObject(
                SchemaProp(
                    "textItemIds",
                    arraySchema("Exact ids to remove.", stringSchema("Text item id.")),
                    required = false,
                ),
                SchemaProp("containingText", stringSchema("Remove items whose text contains this."), required = false),
                SchemaProp("videoId", stringSchema("Remove captions generated from this source."), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_IMAGE_OVERLAY,
            "Place a still image over the video, e.g. a sticker or meme.",
            jsonObject(
                SchemaProp("imageUri", stringSchema("App-provided image identifier or content URI.")),
                SchemaProp("timelineStartMs", integerSchema("Timeline position.", minimum = 0.0)),
                SchemaProp("durationMs", integerSchema("Visible duration.", minimum = 100.0)),
                SchemaProp("x", numberSchema("Centre X, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("y", numberSchema("Centre Y, 0..1.", 0.0, 1.0), required = false),
                SchemaProp("scale", numberSchema("Width as a fraction of the frame.", 0.05, 1.0), required = false),
                SchemaProp("opacity", numberSchema("Opacity, 0..1.", 0.0, 1.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.FREEZE_FRAME,
            "Hold a frame for a moment, e.g. 'freeze when the phone hits the floor'. Optionally shows " +
                "a label over the hold.",
            jsonObject(
                videoId,
                SchemaProp("atSourceMs", integerSchema("Source timestamp to freeze.", minimum = 0.0)),
                SchemaProp("holdMs", integerSchema("How long to hold.", 100.0, 10_000.0), required = false),
                SchemaProp("overlayText", stringSchema("Optional label shown during the hold."), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_TRANSITION,
            "Add a transition at the start or end of a clip.",
            jsonObject(
                SchemaProp("clipId", stringSchema("Clip id.")),
                SchemaProp(
                    "boundary",
                    stringSchema("Which side of the clip.", enum = listOf("start", "end")),
                ),
                SchemaProp(
                    "kind",
                    stringSchema(
                        "Transition style.",
                        enum = listOf("cut", "fade", "cross_dissolve", "slide_left", "slide_up", "wipe", "zoom_blur"),
                    ),
                ),
                SchemaProp("durationMs", integerSchema("Transition length.", 50.0, 3000.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.ADD_MUSIC,
            "Add a music bed from an already-imported audio source, optionally ducking under speech.",
            jsonObject(
                SchemaProp("musicSourceId", stringSchema("Source id of the imported music.")),
                SchemaProp("timelineStartMs", integerSchema("Where the music starts.", minimum = 0.0), required = false),
                SchemaProp("durationMs", integerSchema("How long the music plays.", minimum = 100.0), required = false),
                SchemaProp("volume", numberSchema("Linear gain.", 0.0, 2.0), required = false),
                SchemaProp("duckUnderSpeech", booleanSchema("Lower the music automatically under speech."), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.DUCK_MUSIC,
            "Enable or disable automatic ducking for a music clip.",
            jsonObject(
                SchemaProp("audioClipId", stringSchema("Audio clip id.")),
                SchemaProp("enabled", booleanSchema("Whether ducking is on."), required = false),
                SchemaProp("duckAmountDb", numberSchema("Gain reduction in dB.", -40.0, 0.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.FADE_AUDIO,
            "Apply fade in/out to an audio clip.",
            jsonObject(
                SchemaProp("audioClipId", stringSchema("Audio clip id.")),
                SchemaProp("fadeInMs", integerSchema("Fade-in length.", 0.0, 10_000.0), required = false),
                SchemaProp("fadeOutMs", integerSchema("Fade-out length.", 0.0, 10_000.0), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.BLUR_REGION,
            "Blur a normalised region over a source range, used for redaction such as hiding a phone " +
                "number or a price tag. Coordinates are fractions of the ORIGINAL frame, origin top-left.",
            jsonObject(
                videoId,
                startMs,
                endMs,
                SchemaProp("left", numberSchema("Left edge, 0..1.", 0.0, 1.0)),
                SchemaProp("top", numberSchema("Top edge, 0..1.", 0.0, 1.0)),
                SchemaProp("right", numberSchema("Right edge, 0..1.", 0.0, 1.0)),
                SchemaProp("bottom", numberSchema("Bottom edge, 0..1.", 0.0, 1.0)),
                SchemaProp("trackSubject", booleanSchema("Follow the tracked subject instead of a fixed region."), required = false),
            ),
            ToolCategory.EDITING,
        ),
        ToolDefinition(
            ToolNames.DELETE_EDIT,
            "Revert one earlier edit transaction by id. Use this when the user asks to undo a specific " +
                "previous edit rather than the most recent one, e.g. 'undo the zoom you just added'.",
            jsonObject(
                SchemaProp("targetTransactionId", stringSchema("Transaction id from get_project_state.")),
            ),
            ToolCategory.EDITING,
        ),
    )

    /** Every tool, inspection first so the model sees read-only tools before mutating ones. */
    val definitions: List<ToolDefinition> = inspection + editing

    private val byName: Map<String, ToolDefinition> = definitions.associateBy { it.name }

    val names: Set<String> = byName.keys

    /** Names of tools that mutate the timeline. */
    val mutatingNames: Set<String> = definitions.filter { it.mutatesTimeline }.map { it.name }.toSet()

    /** Names of tools that can return evidence images. */
    val imageProducingNames: Set<String> = definitions.filterNot { it.isTextOnly }.map { it.name }.toSet()

    fun definition(name: String): ToolDefinition? = byName[name]

    fun isKnown(name: String): Boolean = byName.containsKey(name)

    /** JSON schemas in the shape the providers expect. */
    fun schemas(): List<ToolJsonSchema> = definitions.map {
        ToolJsonSchema(name = it.name, description = it.description, parameters = it.parameters)
    }
}

/** Provider-agnostic function description. */
data class ToolJsonSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
