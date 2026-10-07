# Privacy

Helium is local-first. Importing, analysing and editing a clip happen entirely on
your phone; the network is used for exactly one thing — asking the AI model for
the next editing decision — and only the evidence that the AI actually asked for
is ever transmitted.

The guiding rule: **the model proposes edits, it never sees the whole project.**

- [What runs on-device](#what-runs-on-device)
- [The three cloud analysis modes](#the-three-cloud-analysis-modes)
- [Where the policy is enforced](#where-the-policy-is-enforced)
- [What the gateway receives](#what-the-gateway-receives)
- [What is never sent](#what-is-never-sent)
- [Content-free telemetry](#content-free-telemetry)
- [How to revoke or delete](#how-to-revoke-or-delete)

## What runs on-device

Everything except the model's reasoning runs locally, with no network involved:

| Stage | Where it runs | What it produces |
| --- | --- | --- |
| Metadata + EXIF | Device | Duration, resolution, orientation, audio presence |
| Scene detection | Device | Scene boundaries, keyframes, proxies |
| OCR | Device | On-screen text (`find_visible_text`) |
| Audio analysis | Device | Silence, loudness and event candidates |
| Transcript | Device (or your chosen engine) | Spoken words with timestamps |
| Semantic video memory | Device (Room) | Scenes, event packets, temporal strips, overview |
| Timeline editing | Device | `timeline = replay(base, transactions)` |
| Export | Device (Media3 Transformer) | 1080×1920 H.264/AAC MP4 |

The semantic memory — the information-density samples, temporal strips and
`BEFORE → ACTION → AFTER` event packets — is cached in the local Room database,
keyed by source fingerprint. Re-importing the same file re-uses the cache instead
of re-analysing it.

## The three cloud analysis modes

Set in **Settings → Privacy**. The mode is part of `AiModelConfig` and is sent
with every request, so the decision travels with the edit, not with the UI.

| Mode | Frames sent | Text sent |
| --- | --- | --- |
| **Minimize cloud analysis** | **None, ever.** Frames the model asks for are dropped before the request leaves the device. | The instruction and the text evidence the model requested. |
| **Balanced** (default) | A few **low-resolution proxy** frames from the sections the model is inspecting, capped at `maxImagesPerRequest` (default 12) per request. | Same as above. |
| **Allow high-resolution inspection** | Everything in Balanced, plus an **original-resolution frame** when the model explicitly calls `get_original_frame`. | Same as above. |

Even in the permissive modes, images are capped per request and only travel in
response to a tool the model actually called. Helium never uploads the whole
video in any mode.

## Where the policy is enforced

Privacy is enforced at a single seam, in code, not in the UI. Two independent
layers must both agree before a frame leaves the device — this is deliberate, so
a bug in one does not leak data.

1. **`AppToolDispatcher`** (`app/src/main/kotlin/.../ai/AppToolDispatcher.kt`)
   answers evidence requests from data already on disk. It refuses
   `get_original_frame` outright unless the mode is `FULL`, and attaches **no**
   images for `inspect_segment` under `MINIMIZE` (returning a `privacyNote`
   instead so the model falls back to text).

2. **`BackendAiModelProvider`** (`ai/provider/src/main/kotlin/.../BackendAiModelProvider.kt`)
   is the backstop. It **strips every image** from every message under
   `MINIMIZE`, regardless of what the dispatcher produced, and caps the images
   attached to any single request at `maxImagesPerRequest`. What the provider
   builds is the only thing the gateway can see.

The AI also cannot reach the media files themselves: the agent only knows tools.
There is no file-read or render tool. Adding an image-producing tool therefore
requires deliberately routing it through both layers above — see
[docs/AI_TOOLS.md](AI_TOOLS.md).

## What the gateway receives

The `/backend` Ktor gateway is the only component that holds a provider API key;
the key is never in the APK. On each `POST /v1/generate` it receives:

* the model id and instructions,
* the conversation messages — which include the **text** evidence returned by
  tools (transcripts, OCR matches, scene and event summaries),
* the tool schemas,
* the **base64 evidence images** that survived the two enforcement layers above,
* usage counters.

The gateway forwards that request to the configured provider and returns the
model's text and tool calls. It does **not** receive the original media files,
the project document, the full timeline, the source directory, or your imported
files. Its structured logs record counts only — e.g.
`session=… model=… toolCalls=… inputTokens=… outputTokens=… images=…` — never
message text or image bytes.

## What is never sent

* The original video or audio files.
* Proxy frames or keyframes, unless a permitted mode attached them in response to
  a tool call.
* The full project document or timeline.
* Anything at all, when the app has no `backendBaseUrl` configured or is offline.

## Content-free telemetry

The `ai_usage_events` table is used for cost accounting and hard limits. Per the
contract documented in `core/database/.../HeliumDatabase.kt`, each row contains
**only counts and timings**:

`id`, `projectId`, `timestampEpochMs`, `providerId`, `modelId`, `inputTokens`,
`outputTokens`, `reasoningTokens`, `imagesSent`, `toolCalls`, `toolFailures`,
`latencyMs`, `succeeded`.

It deliberately contains **no media, no transcript text and no prompts**, which is
why it is safe to log and to sync. In this build it is stored locally in Room and
is used to show usage totals and enforce limits — it is not uploaded anywhere.

The **Anonymous performance telemetry** toggle (Settings → Privacy, default on)
controls whether this local accounting is recorded. Turning it off stops new rows
from being written; the UI copy is explicit that it covers "timings and error
codes only. Never transcripts, frames or prompts."

## How to revoke or delete

* **Stop all frame transmission:** Settings → Privacy → *Minimize cloud analysis*.
  From this point no image can leave the device, even if the model asks.
* **Stop local telemetry:** Settings → Privacy → turn off *Anonymous performance
  telemetry*.
* **Remove a project's metadata and analysis cache:** delete the project. This
  removes only Helium's own Room rows and the semantic index; your original media
  is never touched by the delete. Deleting a project also deletes its
  `ai_usage_events` rows (`RoomProjectStore.deleteProject`).
* **Remove everything:** uninstall the app. Helium's database, DataStore settings
  and cached proxies live in the app's private storage and go with it.

If you point the app at your own gateway (`backendBaseUrl`), privacy is governed
by that operator in addition to the in-app mode — the mode still guarantees that
no frames are put into the request, but the operator sees the text evidence you
did allow.
