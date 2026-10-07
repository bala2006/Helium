# Helium

Edit short-form video by describing the edit.

Helium is an Android app for Reels, Shorts and Stories. You import clips, then
mostly talk to it: *"cut the boring first eight seconds"*, *"zoom on the coin
flip"*, *"put the product name on screen when I say it"*. The AI plans edits by
calling a deterministic tool API — it never renders video. Every edit is a
transaction over a non-destructive timeline, so undo is exact and export is
reproducible.

**Palette:** `#FECDBE` (cherry blossom) on a dark-first, minimal surface.

## Why it is built this way

* **The model proposes, the app disposes.** The AI returns tool calls; a
  deterministic reducer turns them into timeline operations. Nothing the model
  says can produce a frame the reducer did not compute.
* **Nothing leaves the device by default.** Analysis is local. The gateway is the
  only component holding a provider key, and it is never in the APK.
* **The timeline is a value, not a mutation target.** `timeline = replay(base,
  transactions)`, which is what makes undo free and export reproducible.

Read [docs/ARCHITECTURE.md](ARCHITECTURE.md) before changing anything structural,
[docs/AI_TOOLS.md](AI_TOOLS.md) before touching the tool catalog, and
[docs/PRIVACY.md](PRIVACY.md) before touching evidence gathering.

## Layout

| Path | What it is |
| --- | --- |
| `app/` | Android application: Compose UI, view models, DI container. |
| `core/common/` | Dispatchers, logging, canonical JSON. |
| `core/model/` | Platform-free project, timeline, edit-operation and semantic-memory model. |
| `core/network/` | The gateway wire protocol and its Ktor client. |
| `core/database/` | Room + DataStore persistence. |
| `core/ui/` | Theme and shared Compose components. |
| `editor/domain/` | The deterministic reducer, transaction engine and undo/redo. |
| `ai/tools/` | The tool catalog, JSON schemas, and argument parser. |
| `ai/provider/` | The `AiModelProvider` abstraction and the gateway-backed implementation. |
| `ai/agent/` | The inspection/edit loop that drives a conversation. |
| `media/engine/` | Export planning and the Media3 Transformer renderer. |
| `media/indexer/` | Local analysis: frames, motion, audio, OCR, information density. |
| `backend/` | The Kotlin + Ktor gateway. Its own build, its own deploy. |
| `docs/` | Architecture, tool reference, privacy. |

## Build and test

Requires JDK 17 and the Android SDK (`compileSdk 37.2`, `minSdk 26`).

```bash
# Android app + all unit tests
./gradlew :app:assembleDebug
./gradlew test

# The gateway is a separate, Android-free build
cd backend && ./gradlew test
```

`gradle/libs.versions.toml` is the single source of truth for versions, including
the SDK levels. API 37 ships as minor-versioned platforms, so `compileSdkMinor`
is set alongside `compileSdk` and CI installs `platforms;android-37.2`.

## Run

```bash
cd backend
HELIUM_PROVIDER_API_KEY=sk-... \
HELIUM_SESSION_SECRET=$(openssl rand -hex 32) \
./gradlew run
```

Point the app at that URL in **Settings → AI gateway**, then use **Test
connection**. See [backend/README.md](backend/README.md) for every variable,
endpoint and status code.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request:

* **android** — `:app:assembleDebug`, then `test` across every module, with the
  APK and test reports uploaded as artifacts.
* **backend** — `test`, then `installDist` so the deployable distribution is
  proven to build.

Both jobs must be green to merge.

## Status

The app, the domain engine, the AI orchestration and the gateway are implemented
and covered by tests. Known gaps, kept explicit rather than implied:

* Instrumented (`androidTest`) and macrobenchmark coverage are not written yet.
* Release signing is not configured; only debug builds are produced.
* Export renders framing, crop, speed and audio; zoom ramps, colour grades, blur
  masks, transitions and text are still preview-only. They are never dropped
  silently — `ExportPlan.unsupportedFeatures` surfaces them in the export sheet.
* `ai/agent` defines `AgentStopReason.REPEATED_CALLS` but does not currently set
  it; repeated calls are instead refused per-call and reported as errors.
