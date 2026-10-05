package com.sekhar.helium.core.common

import kotlinx.serialization.json.Json

/**
 * Canonical JSON configuration used for every structured payload in Helium:
 * persisted project state, tool arguments/results and backend traffic.
 *
 * `ignoreUnknownKeys` keeps older clients working when the backend adds fields,
 * which is required because the model configuration is remotely updatable.
 */
val HeliumJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
    allowStructuredMapKeys = true
    classDiscriminator = "kind"
    coerceInputValues = true
}
