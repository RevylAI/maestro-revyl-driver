package ai.revyl.maestro

import maestro.KeyCode
import java.net.URI

internal const val MAX_ERASE_CHARACTERS = 100
internal const val DEFAULT_ERASE_CHARACTERS = 50
internal const val MAX_TAP_COUNT = 100
internal const val MAX_TAP_DELAY_MS = 10_000L
internal const val MAX_EXPANDED_COMMANDS = 1_000
internal const val MAX_CLIPBOARD_BYTES = 16_384
internal const val MAX_FOCUSED_TEXT_BYTES = 16_384
internal const val MAX_SWIPE_DURATION_MS = 10_000L

internal enum class WorkerCapability(val field: String) {
    FOCUSED_TEXT_INPUT("supports_focused_text_input"),
    EXPLICIT_DRAG_DURATION("supports_explicit_drag_duration"),
}

internal fun requireFocusedText(text: String) {
    if (text.contains("\${")) unsupported()
    requireAdapter(text.isNotEmpty(), "Focused text input must not be empty.")
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isHighSurrogate()) {
            requireAdapter(index + 1 < text.length && text[index + 1].isLowSurrogate(), "Focused text input must contain valid Unicode.")
            index += 2
        } else {
            requireAdapter(!character.isLowSurrogate() && (!character.isISOControl() || character in setOf('\t', '\n', '\r')), "Focused text input contains unsupported characters.")
            index++
        }
    }
    requireAdapter(text.toByteArray(Charsets.UTF_8).size <= MAX_FOCUSED_TEXT_BYTES, "Focused text input exceeds the 16 KiB limit.")
}

internal fun requireSwipeDuration(durationMs: Long) {
    requireAdapter(durationMs in 1..MAX_SWIPE_DURATION_MS, "Swipe duration must be an integer from 1 to 10000 milliseconds.")
}

internal fun requireSupportedKey(code: KeyCode, platform: String) {
    if (code !in setOf(KeyCode.ENTER, KeyCode.BACKSPACE, KeyCode.HOME) && !(code == KeyCode.BACK && platform == "android")) unsupported()
}

internal fun requireLocation(latitude: Double, longitude: Double) {
    requireAdapter(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0, "Location requires finite latitude and longitude within their degree ranges.")
}

internal fun requirePlainLink(link: String, autoVerify: Boolean, browser: Boolean) {
    if (autoVerify || browser || link.contains("\${")) unsupported()
    requireAdapter(link.toByteArray(Charsets.UTF_8).size in 1..8_192 && link.none { it.isISOControl() }, "Links must be bounded absolute URIs without control characters.")
    val uri = try { URI(link) } catch (_: Exception) { throw AdapterFailure("Use a valid absolute URI.") }
    requireAdapter(uri.isAbsolute && uri.scheme.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) && uri.rawUserInfo == null, "Use an absolute URI without embedded credentials.")
    requireAdapter(uri.scheme.lowercase() !in setOf("javascript", "data", "file", "intent"), "This URI scheme is unsupported.")
    if (uri.scheme.lowercase() in setOf("http", "https")) requireAdapter(!uri.host.isNullOrEmpty(), "HTTP links require a host.")
}
