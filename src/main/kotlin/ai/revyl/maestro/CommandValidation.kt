package ai.revyl.maestro

import maestro.KeyCode
import java.net.URI

internal const val MAX_ERASE_CHARACTERS = 100
internal const val DEFAULT_ERASE_CHARACTERS = 50
internal const val MAX_TAP_COUNT = 100
internal const val MAX_TAP_DELAY_MS = 10_000L
internal const val MAX_EXPANDED_COMMANDS = 1_000
internal const val MAX_SOURCE_COMMANDS = 999
internal const val MAX_FLOW_DEPTH = 16
internal const val MAX_CLIPBOARD_BYTES = 16_384
internal const val MAX_FOCUSED_TEXT_BYTES = 16_384
internal const val MAX_SWIPE_DURATION_MS = 10_000L

internal enum class WorkerCapability(val field: String) {
    EXPLICIT_DRAG_DURATION("supports_explicit_drag_duration"),
    APP_LIFECYCLE("supports_app_lifecycle"),
    PRECISE_ORIENTATION("supports_precise_orientation"),
    KEYBOARD_CONTROL("supports_keyboard_control"),
    ADD_MEDIA("supports_add_media"),
    APPEARANCE_READ("supports_appearance_read"),
    AIRPLANE_MODE("supports_airplane_mode"),
    SCREEN_RECORDING("supports_screen_recording"),
}

internal const val MAX_MEDIA_FILE_BYTES = 25L * 1024 * 1024
internal const val MAX_RECORDING_BYTES = 100L * 1024 * 1024
internal const val MAX_RANDOM_INPUT_LENGTH = 1_000
internal const val MAX_TRAVEL_POINTS = 20
internal const val TRAVEL_STEPS_PER_SEGMENT = 50
internal const val MAX_TRAVEL_DURATION_MS = 1_800_000L
internal const val MAX_TRAVEL_SPEED_MPS = 10_000.0
internal const val PRIVATE_OUTPUT_DIRECTORY = ".revyl-maestro"

private val mediaNamePattern = Regex("[A-Za-z0-9._-]{1,128}")
private val mediaExtensions = setOf("png", "jpg", "jpeg", "gif", "heic", "mp4", "mov")
private val artifactSegmentPattern = Regex("[A-Za-z0-9_-]{1,80}")

internal fun requireMediaFile(file: java.io.File) {
    requireAdapter(mediaNamePattern.matches(file.name) && file.extension.lowercase() in mediaExtensions,
        "Media files need a simple name of at most 128 characters ending in png, jpg, jpeg, gif, heic, mp4, or mov.")
    val path = file.toPath()
    requireAdapter(java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && java.nio.file.Files.size(path) in 1..MAX_MEDIA_FILE_BYTES,
        "Media files must be nonempty regular files of at most 25 MiB.")
}

internal fun requireArtifactPath(path: String) {
    val segments = path.split('/')
    requireAdapter(!path.contains("\${") && segments.size in 1..8 && segments.all { artifactSegmentPattern.matches(it) },
        "Screenshot and recording paths must be literal relative names of simple segments without extensions or parent references.")
}

internal const val MAX_LAUNCH_ARGUMENTS = 64
internal const val MAX_LAUNCH_ARGUMENT_VALUE_BYTES = 4_096

private val launchArgumentKeyPattern = Regex("[A-Za-z0-9_.-]{1,128}")
private val permissionValues = setOf("allow", "deny", "unset")
private val sharedPermissions = setOf("all", "calendar", "camera", "contacts", "location", "microphone", "notifications", "photos")
private val platformPermissions = mapOf(
    "android" to sharedPermissions + setOf("bluetooth", "phone", "sms", "storage"),
    "ios" to sharedPermissions + setOf("health", "homekit", "medialibrary", "motion", "reminders", "siri", "speech", "userTracking"),
)

internal fun requireAppId(appId: String) {
    requireAdapter(appIdPattern.matches(appId), "Use a literal native app ID.")
}

private val androidPermissionPattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.permission\\.[A-Z][A-Z0-9_]{0,127}")

internal fun requirePermissions(permissions: Map<String, String>, platform: String) {
    val names = platformPermissions.getValue(platform)
    permissions.forEach { (name, value) ->
        requireAdapter(name in names || (platform == "android" && androidPermissionPattern.matches(name)), "Unsupported permission name for this platform.")
        requireAdapter(value in permissionValues, "Permission values must be allow, deny, or unset.")
    }
}

internal fun requireLaunchArguments(arguments: Map<String, Any>) {
    requireAdapter(arguments.size in 1..MAX_LAUNCH_ARGUMENTS, "Launch arguments must contain 1 to 64 entries.")
    arguments.forEach { (key, value) ->
        requireAdapter(launchArgumentKeyPattern.matches(key), "Launch argument names must be 1 to 128 letters, digits, dots, dashes, or underscores.")
        when (value) {
            is String -> {
                if (value.contains("\${")) unsupported()
                requireAdapter(value.toByteArray(Charsets.UTF_8).size <= MAX_LAUNCH_ARGUMENT_VALUE_BYTES && value.none { it.isISOControl() }, "Launch argument values must be at most 4 KiB without control characters.")
            }
            is Boolean, is Int, is Long -> Unit
            is Double -> requireAdapter(value.isFinite(), "Launch argument numbers must be finite.")
            else -> unsupported()
        }
    }
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
