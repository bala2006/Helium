# Build context is this directory: docker build -f backend/Dockerfile backend
#
# The build is self-contained (JDK + the Gradle wrapper only) because the gateway
# is a plain JVM service: it must never need the Android SDK, which is what keeps
# the image small and the deploy independent of the app's release cadence.

# ---- build -------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# Build definition first, so dependency resolution is cached across source edits.
COPY gradle/ gradle/
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon --version

COPY src/ src/
RUN ./gradlew --no-daemon installDist

# ---- run ---------------------------------------------------------------------
FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 helium

WORKDIR /app
COPY --from=build /src/build/install/helium-gateway/ ./

# Never run as root: the process holds the provider credential in memory.
USER helium

ENV PORT=8080
EXPOSE 8080

# No HEALTHCHECK stanza: the JRE image ships no HTTP client. Point your
# orchestrator's probe at GET /health instead; it reports `providerConfigured` so
# a missing key is visible from outside the container.
ENTRYPOINT ["/app/bin/helium-gateway"]
