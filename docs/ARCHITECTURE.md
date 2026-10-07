# Architecture

Helium is a modular Clean/MVVM Android app plus one small server. This document
covers the decisions that are expensive to reverse. Each one is recorded with the
reason it was made, so a future change can argue with the reasoning rather than
rediscovering it.

## Dependency direction

```
app ──> ai:agent ──> ai:provider ──> core:network
 │        │                              │
 │        └──> ai:tools ──> core:model   │
 ├──> media:engine ──> core:model        │
 ├──> media:indexer ──> core:model       │
 ├──> editor:domain ──> core:model       │
 ├──> core:database ──> core:model       │
 └──> core:ui / core:common              │
```

`core:model`, `core:common` and `editor:domain` are **JVM modules with no Android
dependency**. That is not cosmetic: it is why the reducer, the export planner and
the tool parser are unit-testable in milliseconds, and why their tests run in the
same `test` task as everything else without an emulator.

`core:common` exposes `HeliumJson`, one canonical `Json` configuration used for
persisted state, tool arguments/results and backend traffic. `ignoreUnknownKeys`
is deliberate: the model configuration is remotely updatable, so an older client
must survive a newer payload.

## Determinism: the timeline is a pure function

The single most important rule in the codebase:

```
project.timeline == editEngine.replay(baseTimeline, appliedTransactions)
```

Two things follow, and both are load-bearing:

* **Undo needs no inverse operations.** Undoing is re-replaying with the last
  transaction dropped. There is no `UnremoveRangeOp` to get wrong.
* **Export is reproducible.** The same project yields the same frames.

To make that true, `TimelineReducer` is forbidden from consulting a mutable id
generator. It used to take one, and replaying a transaction produced *different
clip ids* each time — a model that was only caught because a test replayed the
same edit twice and compared. Ids are now derived from the operation:
`"${op.operationId}#$suffix"`, and reslice pieces continue the source id
(`"<clipId>#<index>"`, `"<clipId>#a<index>"` for audio).

**If you add an operation, derive its ids from `operationId`. Never call an id
generator from the reducer.**

`EditEngine` also guarantees atomicity: if any part of a transaction fails, the
*original* project is returned, never a half-applied timeline.
`EditHistory.append` truncates the redo stack, so the history cannot branch.

## Audio ownership: one lane per source

A source's audio rides on its own `Clip` (`volume`, `muted`, `hasAudio`) rather
than on a parallel `AudioTrack`. `AudioTrack`s exist only for *added* audio
(music) and are the only tracks with `isMusic = true`.

This was a real bug. `buildBaseTimeline` used to create a parallel "Original
audio" track, which made the timeline's duration outlive the video: trimming a
clip removed video but left its audio behind, and the same source's audio could
be mixed twice. `ExportPlanBuilder` now selects added audio with
`audioTracks.filter { it.isMusic }` and takes original audio from the clips.

## The AI contract: propose, never render

The model's only output channel is a **tool call**. `EditingToolParser` validates
each call into an `EditOperation`, and `TimelineReducer` applies it. There is no
path from model output to a frame that bypasses the reducer, which is what makes
"the AI cannot corrupt your project" a structural property rather than a promise.

The agent loop (`ai/agent`) is bounded on three axes so a misbehaving model cannot
burn a user's quota or their battery:

| Bound | Value | Why |
| --- | --- | --- |
| Tool iterations | `maxToolIterations` | Caps a model that keeps inspecting instead of editing. |
| Identical calls | `MAX_IDENTICAL_CALLS = 2` | Caps an inspection loop; the third is refused with an explanation. |
| Tool result size | `MAX_TOOL_RESULT_CHARS = 6000` | Keeps one verbose tool result from consuming the whole context. |

An unknown tool never reaches the dispatcher, and a malformed argument object
produces a *field-level* validation error the model can correct — never a silently
dropped edit.

## Semantic video memory

Analysis is local and produces a hierarchy, so the model can reason about a
30-minute clip without seeing 54,000 frames.

1. **Measure** (`media/indexer`) — `MediaSignalExtractor` decodes frames and PCM;
   `analysis/InformationDensity` scores how much is changing; `analysis/SceneBuilder`
   groups shots, scenes, motion segments and visual events; `analysis/AudioAnalyzer`
   finds impacts and silence.
2. **Compress** — `SemanticMemory` holds the information-density curve, temporal
   strips and `EventPacket`s. A packet is a `BEFORE → ACTION → AFTER` description
   with an **apex timestamp** and supporting evidence references, so the model
   reasons about a described moment instead of guessing a timestamp.
3. **Inspect on demand** — the tool catalog exposes the hierarchy, level by level:
   `get_video_overview` (cheapest, text) → `search_video` / `get_scene` /
   `get_transcript` → `inspect_segment` / `get_frame` / `get_temporal_strip`
   (images) → `get_original_frame` (most expensive, and gated — see below).

The described text in a packet is a *label for measured behaviour* ("sudden motion
with an impact", "still"), never an invented description. This is the rule that
keeps the memory honest: if a claim is not derived from a measurement, it does not
belong in a packet.

## Privacy is enforced at one seam

`BackendAiModelProvider` is the only class that builds an outbound request, so it
is the only place the policy needs to be enforced:

* `CloudAnalysisMode.MINIMIZE` strips **every** evidence image, so the gateway
  cannot receive frames even if the model explicitly asks for them.
* Otherwise images are capped at `maxImagesPerRequest`.
* `AppToolDispatcher` independently refuses `get_original_frame` unless the mode
  is `FULL`.

Two independent checks guard the same property on purpose: the dispatcher prevents
the expensive *fetch*, the provider prevents the *transmission*. See
[PRIVACY.md](PRIVACY.md).

## Export

`ExportPlanBuilder.build(project, settings, bakedEffects)` is a pure function from
a project to an `ExportPlan` — width, height, codecs, bitrates, clips, audio clips,
text items, and `unsupportedFeatures`. It has no Android dependency, so what will
be rendered is unit-tested without a device.

`ExportClip` always points at the **original** media. The low-resolution proxy is
used for preview and analysis only; an export must be indistinguishable from what
the user shot.

`Media3VideoEngine` renders the plan with Media3's `Transformer`. It declares what
it can bake in `capabilities()`; anything outside that set is reported in
`ExportPlan.unsupportedFeatures` and surfaced in the export sheet. `RenderableEffect.DEFAULT`
is deliberately `emptySet()` — nothing is silently dropped, and a feature is only
marked renderable once it genuinely is.

`ExportPlan.resolveSize` has a subtle but important precedence: explicit override →
preset dimensions → source resolution. It once keyed the preset branch off
`width > 0 && height > 0`, which the presets leave at 0, so choosing "Reel ·
1080×1920" silently produced a *landscape* export. Likewise, "Match original" plus
an explicit 9:16 now renders the 1080p-class canvas for that ratio rather than a
607×1080 lossless crop, unless the source already covers the canvas.

## Toolchain

| Component | Version | Why this one |
| --- | --- | --- |
| AGP | 9.4.1 | AGP 9 has built-in Kotlin, so modules must **not** apply `org.jetbrains.kotlin.android`. |
| Kotlin | 2.3.20 | Newest release with a matching stable KSP. |
| KSP | 2.3.12 | Caps Kotlin, not the other way round. |
| Gradle | 9.6.0 | The AGP 9.4 minimum. |
| compileSdk | 37 (minor 2) | AndroidX and OkHttp 5.x require compiling against API 37. |

Two traps worth recording. First, **API 37 ships as minor-versioned platforms**:
the package is `platforms;android-37.2` and the DSL needs `compileSdkMinor` — a
bare `platforms;android-37` does not exist, and a bare `compileSdk = 37` will not
find the platform. Second, **`kotlin-test` alone does not resolve a test framework
in an Android module** under AGP 9's built-in Kotlin, so those modules declare
`kotlin-test-junit` explicitly. That is why three modules had tests that had never
once compiled.

## The gateway

`backend/` is a standalone Gradle build (JDK 17, no Android SDK) implementing the
protocol in `core:network`'s `GatewayClient.kt`. It exists so that
`HELIUM_PROVIDER_API_KEY` is never in the APK and so the model can be changed
without shipping a release.

One protocol detail is load-bearing: `GatewayMessage` carries an assistant turn's
**tool calls**, not just their results. Providers require each tool output to be
preceded by the matching `function_call` item, and those items cannot be
reconstructed from results alone — the arguments would have to be invented. The
agent records them with the assistant turn and the gateway replays them in order.

State that is in-process (`SlidingWindowRateLimiter`, `IdempotencyStore`,
`InMemoryUsageLedger`) is behind an interface so a multi-replica deployment can
externalise it without touching the routes. See `backend/README.md`.

## Testing strategy

| Layer | How it is tested |
| --- | --- |
| Reducer, history, ranges, crops | Pure JVM unit tests, replay-comparison assertions. |
| Tool catalog, parser | Every declared name is in the catalog; every mutating tool has a parser branch; schemas are strict objects. |
| Agent loop | Scripted provider + fake dispatcher: grounding, bounds, refusal of unknown tools, truncation. |
| Gateway client | `MockEngine`: every status maps to the right exception, plus retry/backoff. |
| Export planning | Pure unit tests against the plan. |
| Gateway | `testApplication` for the full routing stack; `MockEngine` for the provider mapping. |

Assertions are never weakened to make a build pass. When a test and the code
disagreed during development, the disagreement was resolved by deciding which
behaviour was correct and changing whichever side was wrong — most of this
document's "was a real bug" notes are the record of that.
