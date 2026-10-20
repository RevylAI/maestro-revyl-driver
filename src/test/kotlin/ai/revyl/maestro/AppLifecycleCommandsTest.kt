package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLifecycleCommandsTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String): Path = temporary.resolve("${UUID.randomUUID()}.yaml").also {
        Files.writeString(it, "appId: com.example\n---\n$body\n")
    }

    private fun run(backend: LoopbackBackend, body: String): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow(body).toString()), backend.environment, PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    private fun advertise(backend: LoopbackBackend) {
        backend.healthOverrides = mapOf("supports_app_lifecycle" to true, "supports_precise_orientation" to true)
    }

    private fun LoopbackBackend.calls(): List<Pair<String, JsonNode>> = mutations.map { it.path.substringAfterLast('/') to json.readTree(it.body) }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `default launchApp grants all permissions and stops the app before a targeted launch`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- launchApp")
            assertEquals(0, status, output)
            val calls = backend.calls()
            assertEquals(listOf("set_permissions", "kill_app", "launch"), calls.map { it.first })
            assertEquals("com.example", calls[0].second.path("bundle_id").textValue())
            assertEquals("allow", calls[0].second.path("permissions").path("all").textValue())
            assertEquals("com.example", calls[1].second.path("bundle_id").textValue())
            assertEquals(setOf("bundle_id"), calls[2].second.fieldNames().asSequence().toSet())
        }
    }

    @Test fun `full iOS launchApp clears keychain and state, sets permissions, and forwards typed launch arguments`() {
        LoopbackBackend("ios").use { backend ->
            advertise(backend)
            val (status, output) = run(backend, """
                - launchApp:
                    clearState: true
                    clearKeychain: true
                    permissions: {camera: deny, notifications: unset}
                    arguments: {isE2E: true, retries: 3, ratio: 0.5, name: demo}
            """.trimIndent())
            assertEquals(0, status, output)
            val calls = backend.calls()
            assertEquals(listOf("clear_keychain", "clear_app_state", "set_permissions", "kill_app", "launch"), calls.map { it.first })
            assertEquals("deny", calls[2].second.path("permissions").path("camera").textValue())
            val arguments = calls[4].second.path("arguments")
            assertTrue(arguments.path("isE2E").isBoolean && arguments.path("isE2E").booleanValue())
            assertEquals(3, arguments.path("retries").intValue())
            assertEquals(0.5, arguments.path("ratio").doubleValue())
            assertEquals("demo", arguments.path("name").textValue())
        }
    }

    @Test fun `Android clearKeychain is a local no-op like Maestro's Android driver`() {
        LoopbackBackend("android").use { backend ->
            val (status, output) = run(backend, "- clearKeychain")
            assertEquals(0, status, output)
            assertTrue(backend.mutations.isEmpty())
            assertFalse(backend.requests.any { it.path.endsWith("/health") })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `standalone lifecycle commands target the flow app and clearState also resets permissions`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, """
                - stopApp
                - clearState: com.other.app
                - setPermissions: {permissions: {location: allow}}
                - setOrientation: LANDSCAPE_LEFT
                - setOrientation: UPSIDE_DOWN
            """.trimIndent())
            assertEquals(0, status, output)
            val calls = backend.calls()
            assertEquals(listOf("kill_app", "clear_app_state", "set_permissions", "set_permissions", "set_orientation", "set_orientation"), calls.map { it.first })
            assertEquals("com.example", calls[0].second.path("bundle_id").textValue())
            assertEquals("com.other.app", calls[1].second.path("bundle_id").textValue())
            assertEquals("unset", calls[2].second.path("permissions").path("all").textValue())
            assertEquals("allow", calls[3].second.path("permissions").path("location").textValue())
            assertEquals(listOf("landscape_left", "upside_down"), calls.drop(4).map { it.second.path("orientation").textValue() })
        }
    }

    @Test fun `Android accepts fully qualified permission names like Maestro`() {
        LoopbackBackend("android").use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- setPermissions: {permissions: {android.permission.MANAGE_EXTERNAL_STORAGE: allow}}")
            assertEquals(0, status, output)
            assertEquals("allow", backend.calls().single().second.path("permissions").path("android.permission.MANAGE_EXTERNAL_STORAGE").textValue())
        }
        LoopbackBackend("ios").use { backend ->
            assertEquals(1, run(backend, "- setPermissions: {permissions: {android.permission.CAMERA: allow}}").first)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test fun `iOS killApp terminates the targeted app`() {
        LoopbackBackend("ios").use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- killApp")
            assertEquals(0, status, output)
            assertEquals(listOf("kill_app"), backend.calls().map { it.first })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["- launchApp", "- stopApp", "- clearState", "- setPermissions: {permissions: {camera: allow}}", "- setOrientation: PORTRAIT",
        "- launchApp: {stopApp: false, permissions: {}, arguments: {a: b}}"])
    fun `lifecycle commands fail closed before any mutation when the worker does not advertise them`(command: String) {
        LoopbackBackend("ios").use { backend ->
            val (status, output) = run(backend, "- pressKey: Enter\n$command")
            assertEquals(1, status, command)
            assertTrue(backend.mutations.isEmpty(), command)
            assertTrue(output.contains("does not advertise"), output)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["- setPermissions: {permissions: {camera: maybe}}", "- setPermissions: {permissions: {sms: allow}}",
        "- setPermissions: {permissions: {unknownThing: allow}}", "- launchApp: {arguments: {'bad key': 1}}",
        "- launchApp: {arguments: {name: '\${1 + 1}'}}", "- stopApp: 'not an app id'", "- clearState: '\${1}'"])
    fun `invalid lifecycle options fail before any request`(command: String) {
        LoopbackBackend("ios").use { backend ->
            val (status, _) = run(backend, "- pressKey: Enter\n$command")
            assertEquals(1, status, command)
            assertTrue(backend.requests.isEmpty(), command)
        }
    }

    @Test fun `Android killApp stays unsupported because process death is not a force-stop`() {
        LoopbackBackend("android").use { backend ->
            val (status, _) = run(backend, "- killApp")
            assertEquals(1, status)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test fun `validate-only reports required capabilities without any network access`() {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--validate-only", "--platform", "ios", flow("- launchApp\n- setOrientation: PORTRAIT").toString()), emptyMap(), PrintStream(output), PrintStream(output))
        assertEquals(0, status, output.toString())
        assertTrue(output.toString().contains("supports_app_lifecycle, supports_precise_orientation"), output.toString())
        val accepted = ByteArrayOutputStream()
        assertEquals(0, runCli(arrayOf("--validate-only", "--platform", "ios", flow("- hideKeyboard").toString()), emptyMap(), PrintStream(accepted), PrintStream(accepted)))
        assertTrue(accepted.toString().contains("supports_keyboard_control"), accepted.toString())
    }
}
