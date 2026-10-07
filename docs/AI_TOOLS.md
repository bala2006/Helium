# AI tools

The model's only way to change a project is to call one of these tools. This
document is the reference for adding, changing or debugging one.

Every tool lives in `ai/tools`:

* `ToolModels.kt` — `ToolNames`, and the request/result types.
* `ToolSchema.kt` — JSON Schema construction helpers.
* `ToolCatalog.kt` — the definition of every tool: name, description, schema,
  and whether it mutates the timeline or produces images.
* `EditingToolParser.kt` — validates arguments into an `EditOperation`.

## The contract

1. **Nothing is applied that the parser did not validate.** A malformed argument
   object produces a field-level validation error that is fed back to the model so
   it can correct itself — never a silently dropped edit.
2. **Read tools describe; write tools return operations.** A read tool returns
   text and/or evidence images. A write tool returns an `EditOperation` that the
   reducer applies. No tool mutates a timeline directly.
3. **Schemas are strict objects.** `type: "object"`, `additionalProperties: false`,
   and every declared `required` key must also exist in `properties`. A
   zero-argument tool declares an empty `properties` object and omits `required`.
4. **Names are snake_case** and are declared once in `ToolNames`. The catalog and
   the parser both read from it, and tests assert the two cannot drift apart.

## Read tools

These inspect the project or its semantic memory. They never change an edit.

### Project state

| Tool | Arguments | Returns |
| --- | --- | --- |
| `get_project_state` | *(none)* | The current timeline, tracks, clips, and pending edits. |

### Semantic memory (level 1 — cheapest, text only)

| Tool | Arguments | Returns |
| --- | --- | --- |
| `get_video_overview` | *(optional time range)* | The whole-clip summary: shots, scenes, transcript outline, information-density highlights. Start here. |
| `search_video` | `query` | Moments matching a natural-language description. |
| `get_scene` | `sceneId` or timestamp | One scene's boundaries and description. |
| `get_transcript` | *(optional range)* | Spoken text with per-word timings. |
| `find_spoken_text` | `query` | Where a phrase is said. |
| `find_visible_text` | `query` | On-screen text (OCR) matches, with bounding boxes as fractions of the original frame. |
| `get_audio_events` | *(optional range)* | Impacts, silence, loudness changes. |
| `get_visual_events` | *(optional range)* | Shot boundaries, motion onsets, static holds, text appearing/disappearing, brightness changes. |
| `find_high_motion_segments` | *(optional range)* | The most dynamic stretches. |
| `find_silence` | *(optional range)* | Silent gaps, for trimming dead air. |

`EventPacket`s are what make this cheap: each is a `BEFORE → ACTION → AFTER`
description with an **apex timestamp**, so the model reasons about a described
moment instead of guessing a timestamp from raw frames.

### Evidence (levels 2–4 — images, increasingly expensive)

| Tool | Arguments | Cost | Returns |
| --- | --- | --- | --- |
| `get_temporal_strip` | `range`, `count` | Low | A filmstrip across a period: one image, several moments. |
| `inspect_segment` | `range`, `count` | Medium | Frames from the low-resolution **proxy** at the requested moments. |
| `get_frame` | `timestampMs` | Medium | One proxy frame. |
| `get_original_frame` | `timestampMs` | **High** | One full-resolution frame. **Gated — see below.** |

Escalate only when the cheaper level is genuinely ambiguous. The prompt tells the
model this; the tool descriptions reinforce it.

`get_original_frame` is refused unless `CloudAnalysisMode.FULL`. The dispatcher
blocks the fetch and `BackendAiModelProvider` strips evidence images entirely under
`MINIMIZE`, so the expensive, most revealing path is closed by two independent
checks. See [PRIVACY.md](PRIVACY.md).

## Edit tools

These are the only tools whose calls become timeline operations.

| Tool | Key arguments | Effect |
| --- | --- | --- |
| `remove_range` | `videoId`, `startMs`, `endMs` | Cut a span out and close the gap. |
| `restore_range` | `videoId`, `startMs`, `endMs` | Bring back a previously removed span. |
| `trim_clip` | `clipId`, `startMs`, `endMs` | Shorten a clip to a new source range. |
| `split_clip` | `clipId`, `atMs` | Split into two clips. |
| `move_clip` | `clipId`, `toIndex` | Reorder. |
| `duplicate_clip` | `clipId` | Copy a clip in place. |
| `set_speed` | `videoId`/`clipId`, range, `speed` | Change playback rate; retimes the span. |
| `set_volume` | range, `volume` | Set clip volume. |
| `mute_range` | range | Silences a span without removing it. |
| `set_aspect_ratio` | `aspectRatio` (`9:16`, `16x9`, `1:1`, `4:5`, `original`) | Change the output framing. |
| `crop_clip` | `clipId`, `left`/`top`/`right`/`bottom` | Crop, as fractions of the original frame. |
| `reframe_clip` | `clipId`, `focusX`/`focusY` | Recompose the framing around a subject. |
| `add_zoom` | range, `scale`, optional `focusX`/`focusY`, `easeInMs` | Zoom ramp. |
| `add_text` | `text`, range, optional style | Title text. |
| `add_caption` | `text`, range, optional per-word timings | Caption, with word highlighting. |
| `remove_caption` | `captionId` | Remove a caption. |
| `add_image_overlay` | `path`/`uri`, range, position | Overlay an image. |
| `freeze_frame` | `timestampMs`, `holdMs` | Hold a frame. |
| `add_transition` | `clipId`, `type`, `durationMs` | Transition between clips. |
| `add_music` | `uri`, range, `volume` | Add an audio bed (`isMusic` track). |
| `duck_music` | range | Lower music under speech. |
| `fade_audio` | range, `fadeInMs`/`fadeOutMs` | Audio fades. |
| `blur_region` | range, `left`/`top`/`right`/`bottom` | Blur a region, e.g. to hide a face or a plate. |
| `delete_edit` | **`targetTransactionId`** | Undo a single earlier edit by id. |

`delete_edit` takes `targetTransactionId`, not `transactionId`. It is handled by
`EditEngine` *before* replay, because removing a transaction changes what the rest
of the history means.

Timestamps are milliseconds from the start of the source. Coordinates are
fractions of the **original** frame, origin top-left, so a crop is meaningful
regardless of the proxy's resolution.

## Adding a tool

1. Add the name to `ToolNames` in `ToolModels.kt`. The catalog test will fail until
   it exists in `ToolCatalog`, and the parser test will fail until a mutating tool
   has a branch in `EditingToolParser`.
2. Add the definition to `ToolCatalog.kt` with a strict schema. Say plainly in the
   description *when to use it*, and mention the cheaper alternative when one
   exists.
3. Add the parser branch. Validate bounds and ordering here — the reducer must
   never receive an inverted range. Reject rather than clamp: a silent clamp turns
   a model mistake into a wrong edit the user cannot explain.
4. If it is a new op type, teach `TimelineReducer` to apply it, and **derive its
   ids from `operationId`** (see `ARCHITECTURE.md`).
5. Update the reducer tests, and this table.

## What the model is not allowed to do

* Render, encode, or produce a frame. It plans; the reducer and the media engine
  execute.
* Name a file path, a URI, or a provider key it was not given.
* Delete a project, or clear the history.
* Escape the state it was shown: an id that was not in the prompt is rejected by
  the parser.

These are enforced structurally, not by asking the model nicely. A tool that is
not in the catalog cannot be called; a call whose arguments do not validate
produces an error, not an operation.
