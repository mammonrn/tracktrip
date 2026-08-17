package app.ptrip.tracktrip.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The backend's base URL, for the one thing in the UI that needs it: turning a
 * stored `photo_url` into something an image loader can fetch.
 *
 * The server stores uploaded avatars as a path (`/uploads/avatars/x.jpg`)
 * rather than a URL, because the host is not its to know — and the same column
 * still holds absolute Google URLs for riders who never uploaded one. So both
 * forms are in circulation, and [resolveMediaUrl] is the single place that
 * tells them apart.
 */
val LocalApiBaseUrl = staticCompositionLocalOf { "" }

/** An absolute URL for [path], which may already be one. */
fun resolveMediaUrl(baseUrl: String, path: String?): String? {
    val trimmed = path?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    return baseUrl.trimEnd('/') + "/" + trimmed.trimStart('/')
}
