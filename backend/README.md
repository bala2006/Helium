# Helium gateway

The only component that holds a model-provider credential.

The Android app speaks one small, stable protocol (`:core:network`'s
`GatewayClient.kt`) and this service translates it to a provider. That is what
lets the model change without shipping a new APK, and it is why
`HELIUM_PROVIDER_API_KEY` is never present on a device.

This is a standalone Gradle build. It needs a JDK 17 — **not** the Android SDK —
so it builds and deploys independently of the app.

## Run it

```bash
cd backend
HELIUM_PROVIDER_API_KEY=sk-... \
HELIUM_SESSION_SECRET=$(openssl rand -hex 32) \
./gradlew run
```

### Container

The build context is this directory:

```bash
docker build -f backend/Dockerfile backend -t helium-gateway
docker run --rm -p 8080:8080 \
  -e HELIUM_PROVIDER_API_KEY=sk-... \
  -e HELIUM_SESSION_SECRET=$(openssl rand -hex 32) \
  helium-gateway
```

The image runs as a non-root user and exposes `GET /health` for probes.

## Configuration

Read once at boot from the environment. Secrets are never logged or echoed.

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `HELIUM_PROVIDER_API_KEY` | yes | — | Provider credential. Without it `/v1/generate` cannot reach a model. |
| `HELIUM_SESSION_SECRET` | yes | — | HMAC key for session tokens. Without it every `/v1/generate` answers `503`. |
| `PORT` | no | `8080` | Listen port. |
| `HELIUM_HOST` | no | `0.0.0.0` | Listen address. |
| `HELIUM_PROVIDER_BASE_URL` | no | `https://api.openai.com/v1` | Provider base URL. |
| `HELIUM_DEFAULT_MODEL` | no | `gpt-6-luna` | Used when a client sends a blank model id. |
| `HELIUM_SESSION_LIFETIME_SECONDS` | no | `43200` | Session token lifetime. |
| `HELIUM_REQUESTS_PER_MINUTE` | no | `30` | Per-session budget, reported as `Retry-After`. |
| `HELIUM_GLOBAL_REQUESTS_PER_MINUTE` | no | `600` | Deployment-wide ceiling. |
| `HELIUM_MAX_IMAGES_PER_REQUEST` | no | `8` | Hard cap on evidence images, applied after the app's own privacy policy. |
| `HELIUM_MAX_REQUEST_BYTES` | no | `8388608` | Bodies larger than this are rejected before parsing. |
| `HELIUM_IDEMPOTENCY_TTL_SECONDS` | no | `900` | How long a completed response stays replayable. |
| `HELIUM_ALLOWED_ORIGINS` | no | *(empty)* | Comma-separated CORS origins for tooling. |

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness plus `providerConfigured`. Always `200` so a client can tell "no gateway" from "gateway without a key". |
| `POST` | `/v1/session` | Mints a session token for `{"clientId": "..."}`. |
| `POST` | `/v1/generate` | The proxy. Requires `Authorization: Bearer <session token>`. |
| `GET` | `/v1/usage` | Per-session totals for the calling token. |

### `/v1/generate` pipeline

Every request passes through these in order, and no step is skippable:

1. **Payload bound** — oversized bodies are rejected before they are parsed.
2. **Authentication** — a token from `Authorization` (or the `sessionToken` field
   for clients that cannot set headers). No valid token, no provider call.
3. **Rate limit** — deployment-wide first, then per session, both with an exact
   `Retry-After`.
4. **Idempotency** — a repeated `Idempotency-Key` returns the stored response, so
   a client retry is never charged twice. Keys are scoped per session.
5. **Proxy** — the protocol is translated to the provider.
6. **Accounting** — tokens, evidence images and an estimated cost are recorded
   per session and readable at `/v1/usage`.

### Status codes the app relies on

| Code | Meaning | What the app does |
| --- | --- | --- |
| `200` | Success | Applies the reply. |
| `400` / `413` / `422` | Malformed or oversized request | Reports a bug; does not retry. |
| `401` / `403` | Missing, expired or forged token | Sends the user to sign in again. |
| `408` / `504` | Timeout | Retries with backoff. |
| `429` | Rate limited (ours or the provider's) | Retries after `Retry-After`. |
| `502` / `5xx` | Provider or gateway fault | Retries with backoff. |

A provider rejection is deliberately reported as `502`, not `4xx`: from the app's
point of view a provider-side failure is a gateway fault, and `4xx` means "your
bug, do not retry".

## Scaling notes

Two things are in-process and must be externalised before running more than one
replica:

* **Rate limiting** (`SlidingWindowRateLimiter`) — per-session budgets are only
  correct across replicas behind a shared store such as Redis. The `RateLimiter`
  interface exists so the swap does not touch the routes.
* **Idempotency and usage** (`IdempotencyStore`, `InMemoryUsageLedger`) — both are
  in-memory, so a restart forgets them and a second replica will not see the
  first one's keys. Implement `UsageLedger` over your database to keep history.

`/v1/session` is the seam for real accounts. Helium ships without user accounts,
so the gateway signs whatever `clientId` it is given and the HMAC still makes
forged tokens impossible. A deployment with users must verify them here, or in
front of this route, before calling `SessionTokens.mint`.

## Tests

```bash
cd backend && ./gradlew test
```

`GatewayRoutesTest` drives the real routing stack (auth, limits, idempotency,
accounting, failure mapping) through `testApplication`;
`OpenAiResponsesModelTest` pins the provider mapping — request shape and response
parsing — against a mock engine.
