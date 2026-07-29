package com.github.aljge.tensorspeak

/** A provider-agnostic, pure description of one outgoing TTS HTTP call. */
data class CloudTtsHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val jsonBody: String,
)

/** Joins a base URL and a path without producing a double or missing slash. */
internal fun joinUrl(baseUrl: String, path: String): String =
    baseUrl.trimEnd('/') + "/" + path.trimStart('/')
