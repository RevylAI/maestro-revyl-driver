package ai.revyl.maestro

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class PermissionAlertsTest {
    @TempDir lateinit var temporary: Path

    private fun run(backend: LoopbackBackend, body: String): Pair<Int, String> {
        val flow = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(flow, "appId: com.example\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", "ios", flow.toString()), backend.environment, PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    private fun serveScreens(backend: LoopbackBackend, screens: List<List<Map<String, Any>>>) {
        backend.healthOverrides = mapOf("supports_app_lifecycle" to true, "supports_extended_ios_permissions" to true)
        val taps = AtomicInteger()
        backend.intercept = { request ->
            when {
                request.path.endsWith("/hierarchy") -> Reply(bytes = json.writeValueAsBytes(listOf(mapOf(
                    "type" to "Application", "frame" to frame(0, 0, 440, 956), "children" to screens[minOf(taps.get(), screens.lastIndex)],
                ))))
                request.path.endsWith("/screenshot") -> Reply(bytes = png(1320, 2868))
                request.path.endsWith("/tap") -> {
                    taps.incrementAndGet()
                    Reply(bytes = json.writeValueAsBytes(mapOf("success" to true, "action" to "tap")))
                }
                else -> null
            }
        }
    }

    private fun taps(backend: LoopbackBackend): List<Pair<Int, Int>> = backend.mutations.filter { it.path.endsWith("/tap") }
        .map { json.readTree(it.body).let { body -> body.path("x").intValue() to body.path("y").intValue() } }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "motion|Allow \u201cProbe\u201d to access your motion and fitness activity?",
        "motion|\u201cProbe\u201d Would Like to Access Your Motion & Fitness Activity",
        "camera|Allow \u201cProbe\u201d to access your camera?",
        "microphone|Allow \u201cProbe\u201d to access your microphone?",
        "contacts|Allow \u201cProbe\u201d full access to your contacts?",
        "photos|Allow \u201cProbe\u201d to access your photo library?",
        "homekit|Allow \u201cProbe\u201d to access your Home data?",
        "motion|\u201cProbe\u201d would like to access your Motion & Fitness activity.",
        "camera|\u201cProbe\u201d Would Like to Access the Camera",
        "camera|\u201cProbe\u201d would like to access the Camera.",
        "microphone|\u201cProbe\u201d Would Like to Access the Microphone",
        "microphone|\u201cProbe\u201d would like to access the Microphone.",
        "contacts|\u201cProbe\u201d Would Like to Access Your Contacts",
        "contacts|\u201cProbe\u201d would like to access your Contacts.",
        "contacts|Allow \u201cProbe\u201d to access your contacts?",
        "calendar|\u201cProbe\u201d Would Like to Access Your Calendar",
        "calendar|\u201cProbe\u201d Would Like Full Access to Your Calendar",
        "calendar|\u201cProbe\u201d would like to access your Calendar.",
        "calendar|\u201cProbe\u201d would like full access to your Calendar.",
        "calendar|Allow \u201cProbe\u201d to access your calendar?",
        "calendar|Allow \u201cProbe\u201d full access to your calendar?",
        "reminders|\u201cProbe\u201d Would Like to Access Your Reminders",
        "reminders|\u201cProbe\u201d would like to access your Reminders.",
        "reminders|Allow \u201cProbe\u201d to access your reminders?",
        "photos|\u201cProbe\u201d Would Like to Access Your Photo Library",
        "photos|\u201cProbe\u201d would like to access your Photo Library.",
        "photos|Allow \u201cProbe\u201d full access to your photo library?",
        "homekit|\u201cProbe\u201d Would Like to Access Your Home Data",
        "homekit|\u201cProbe\u201d would like to access your Home Data.",
        "medialibrary|\u201cProbe\u201d would like to access Apple Music, your music and video activity, and your media library",
        "medialibrary|\u201cProbe\u201d would like to access Apple Music, your music and video activity, and your media library.",
        "medialibrary|Allow \u201cProbe\u201d to access your music, video activity, and media library in Apple Music?",
        "speech|\u201cProbe\u201d Would Like to Access Speech Recognition",
        "speech|\u201cProbe\u201d would like to access Speech Recognition.",
        "speech|Allow \u201cProbe\u201d to access what is said using speech recognition?",
        "location|Allow \u201cProbe\u201d to use your location?",
        "notifications|\u201cProbe\u201d Would Like to Send You Notifications",
        "userTracking|Allow \u201cProbe\u201d to track your activity across other companies\u2019 apps and websites?",
    ])
    fun `iOS 18 to 27 runtime permission alert titles are answered`(permission: String, title: String) {
        LoopbackBackend("ios").use { backend ->
            serveScreens(backend, listOf(twoButtonAlert(title), readyScreen))
            val (status, output) = run(backend, "- launchApp: {permissions: {$permission: allow}}\n- assertVisible: Ready")
            assertEquals(0, status, output)
            assertEquals(listOf(294 to 595), taps(backend))
        }
    }

    @Test
    fun `iOS alert behind an iOS 27 motion alert is answered after it`() {
        LoopbackBackend("ios").use { backend ->
            serveScreens(backend, listOf(twoButtonAlert("Allow \u201cProbe\u201d to access your motion and fitness activity?"), locationAlert, readyScreen))
            val (status, output) = run(backend, "- launchApp: {permissions: {motion: deny, location: allow}}\n- assertVisible: Ready")
            assertEquals(0, status, output)
            assertEquals(listOf(146 to 595, 220 to 539), taps(backend))
        }
    }

    @Test
    fun `alert left from an earlier launch is answered before the grant it would overwrite`() {
        LoopbackBackend("ios").use { backend ->
            serveScreens(backend, listOf(locationAlert, readyScreen))
            val (status, output) = run(backend, "- launchApp: {permissions: {location: allow}}\n- assertVisible: Ready")
            assertEquals(0, status, output)
            assertEquals(listOf(220 to 539), taps(backend))
            val order = backend.mutations.map { it.path.substringAfterLast('/') }.filter { it in setOf("tap", "set_permissions", "launch") }
            assertEquals(listOf("tap", "set_permissions", "launch"), order)
        }
    }

    @Test
    fun `a grant leaves alerts for permissions it does not name on screen`() {
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = mapOf("supports_app_lifecycle" to true, "supports_extended_ios_permissions" to true)
            backend.intercept = { request ->
                val granted = backend.mutations.any { it.path.endsWith("/set_permissions") }
                when {
                    request.path.endsWith("/hierarchy") -> Reply(bytes = json.writeValueAsBytes(listOf(mapOf(
                        "type" to "Application", "frame" to frame(0, 0, 440, 956), "children" to if (granted) locationAlert else readyScreen,
                    ))))
                    request.path.endsWith("/screenshot") -> Reply(bytes = png(1320, 2868))
                    else -> null
                }
            }
            val (status, output) = run(backend, "- setPermissions: {permissions: {location: allow}}\n- setPermissions: {permissions: {motion: allow}}")
            assertEquals(0, status, output)
            assertEquals(emptyList(), taps(backend))
            assertEquals(2, backend.mutations.count { it.path.endsWith("/set_permissions") })
        }
    }

    @Test
    fun `usage description naming another permission does not decide the alert`() {
        LoopbackBackend("ios").use { backend ->
            val contactsAlert = twoButtonAlert("Allow \u201cProbe\u201d full access to your contacts?").map { node ->
                if (node["AXLabel"] == "Probe test.") node + ("AXLabel" to "We use your location to find friends near you.") else node
            }
            serveScreens(backend, listOf(contactsAlert, readyScreen))
            val (status, output) = run(backend, "- launchApp: {permissions: {contacts: deny, location: allow}}\n- assertVisible: Ready")
            assertEquals(0, status, output)
            assertEquals(listOf(146 to 595), taps(backend))
        }
    }

    private companion object {
        fun frame(x: Number, y: Number, width: Number, height: Number) = mapOf("x" to x, "y" to y, "width" to width, "height" to height)

        fun node(type: String, label: String, frame: Map<String, Number>) = mapOf("type" to type, "AXLabel" to label, "enabled" to true, "frame" to frame)

        fun twoButtonAlert(title: String) = listOf(
            node("StaticText", title, frame(90, 460.33, 260, 64.33)),
            node("StaticText", "Probe test.", frame(90, 532.33, 260, 18)),
            node("Button", "Don\u2019t Allow", frame(76, 570.67, 140, 48)),
            node("Button", "Allow", frame(224, 570.67, 140, 48)),
        )

        val locationAlert = listOf(
            node("StaticText", "Allow \u201cProbe\u201d to use your location?", frame(90, 371, 260, 42.33)),
            node("StaticText", "Probe test: location permission.", frame(90, 420.67, 260, 18)),
            node("Button", "Allow Once", frame(76, 459, 288, 48)),
            node("Button", "Allow While Using App", frame(76, 515, 288, 48)),
            node("Button", "Don\u2019t Allow", frame(76, 571, 288, 48)),
        )

        val readyScreen = listOf(node("StaticText", "Ready", frame(16, 78, 120, 34)))
    }
}
