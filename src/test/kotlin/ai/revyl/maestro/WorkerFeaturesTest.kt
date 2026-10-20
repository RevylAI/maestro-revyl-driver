package ai.revyl.maestro

import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.stream.Stream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerFeaturesTest {
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
        backend.healthOverrides = capabilities.associateWith { true }
    }

    private fun assertMutations(backend: LoopbackBackend, expected: List<Pair<String, Map<String, Any>>>) {
        assertEquals(expected.map { "/api/v1/execution/device-proxy/$WORKFLOW/${it.first}" }, backend.mutations.map { it.path })
        expected.zip(backend.mutations).forEach { (action, request) ->
            assertEquals(json.valueToTree(action.second), json.readTree(request.body))
        }
        assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
    }

    private fun assertPrivate(path: Path) {
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.parent))
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `hideKeyboard posts an empty body and verifies keyboard visibility`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- hideKeyboard\n- pressKey: Enter")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("hide_keyboard" to emptyMap(), "key" to mapOf("key" to "ENTER")))
            val hide = backend.requests.indexOfFirst { it.path.endsWith("/hide_keyboard") }
            val read = backend.requests.indexOfFirst { it.path.endsWith("/keyboard") }
            val enter = backend.requests.indexOfFirst { it.path.endsWith("/key") }
            assertTrue(hide < read && read < enter)
            assertEquals("GET", backend.requests[read].method)
            assertFalse(backend.keyboardVisible)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `appearance assertions and toggles use live reads and exact appearance writes`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- assertLightMode\n- toggleDarkMode\n- assertDarkMode\n- toggleDarkMode\n- assertLightMode")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("set_appearance" to mapOf("appearance" to "dark"), "set_appearance" to mapOf("appearance" to "light")))
            assertEquals(5, backend.requests.count { it.path.endsWith("/appearance") && it.method == "GET" })
            assertEquals("light", backend.appearance)
        }
    }

    @Test fun `Android airplane mode sets and toggles strict boolean state`() {
        LoopbackBackend().use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- setAirplaneMode: enabled\n- toggleAirplaneMode\n- toggleAirplaneMode\n- setAirplaneMode: disabled")
            assertEquals(0, status, output)
            assertMutations(backend, listOf(true, false, true, false).map { "set_airplane_mode" to mapOf("enabled" to it) })
            assertEquals(2, backend.requests.count { it.path.endsWith("/airplane_mode") && it.method == "GET" })
            assertFalse(backend.airplaneMode)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["- setAirplaneMode: enabled", "- toggleAirplaneMode"])
    fun `iOS airplane commands fail preflight before earlier keys or attachment`(command: String) {
        LoopbackBackend("ios").use { backend ->
            advertise(backend)
            assertEquals(1, run(backend, "- pressKey: Enter\n$command").first)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `addMedia sends one exact basename and base64 payload per file in order`(platform: String) {
        val photo = temporary.resolve("${UUID.randomUUID()}.png").also { Files.write(it, png(2, 3)) }
        val video = temporary.resolve("${UUID.randomUUID()}.mp4").also { Files.write(it, MP4_FIXTURE) }
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, "- addMedia: [${json.writeValueAsString(photo.fileName.toString())}, ${json.writeValueAsString(video.fileName.toString())}]")
            assertEquals(0, status, output)
            assertMutations(backend, listOf(photo, video).map {
                "add_media" to mapOf("name" to it.fileName.toString(), "content_base64" to Base64.getEncoder().encodeToString(Files.readAllBytes(it)))
            })
            assertFalse(output.contains(Base64.getEncoder().encodeToString(MP4_FIXTURE)))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `recording uses the returned identifier and writes private MP4 bytes only once`(platform: String) {
        val name = UUID.randomUUID().toString()
        val artifact = Path.of(".revyl-maestro", "$name.mp4")
        try {
            LoopbackBackend(platform).use { backend ->
                advertise(backend)
                val (status, output) = run(backend, "- startRecording: $name\n- stopRecording\n- pressKey: Enter")
                assertEquals(0, status, output)
                assertMutations(backend, listOf("start_screen_recording" to emptyMap(), "stop_screen_recording" to mapOf("recording_id" to "rec-1"), "key" to mapOf("key" to "ENTER")))
                assertContentEquals(MP4_FIXTURE, Files.readAllBytes(artifact))
                assertPrivate(artifact)
            }
        } finally { Files.deleteIfExists(artifact) }
    }

    @ParameterizedTest
    @MethodSource("capabilityFailures")
    fun `all new capabilities fail closed before an earlier key`(platform: String, command: String, capability: String, value: String) {
        val media = temporary.resolve("fixture.png").also { Files.write(it, png(1, 1)) }
        val resolved = command.replace("MEDIA", media.fileName.toString()).replace("RECORDING", UUID.randomUUID().toString())
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            backend.healthOverrides = if (value == "missing") backend.healthOverrides - capability else backend.healthOverrides + (capability to json.readValue(value, Any::class.java))
            val (status, output) = run(backend, "- pressKey: Enter\n$resolved")
            assertEquals(1, status, "$platform $command $value: $output")
            assertTrue(backend.mutations.isEmpty())
            assertEquals(listOf("/api/v1/execution/device-sessions/$SESSION", "/api/v1/execution/device-proxy/$WORKFLOW/health"), backend.requests.map { it.path })
        }
    }

    @ParameterizedTest
    @MethodSource("malformedReads")
    fun `malformed device state is terminal before any later mutation`(platform: String, command: String, route: String, payload: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            backend.intercept = { if (it.path.endsWith("/$route")) Reply(bytes = payload.toByteArray()) else null }
            val (status, output) = run(backend, "$command\n- setDarkMode: enabled\n- pressKey: Enter")
            assertEquals(1, status, "$route $payload: $output")
            assertEquals(if (route == "keyboard") listOf("hide_keyboard") else emptyList(), backend.mutations.map { it.path.substringAfterLast('/') })
            assertEquals(1, backend.requests.count { it.path.endsWith("/$route") })
            assertFalse(output.contains(PRIVATE_SENTINEL))
        }
    }

    @ParameterizedTest
    @MethodSource("uncertainWrites")
    fun `uncertain feature writes are never replayed and block all continuation`(platform: String, route: String, command: String, payload: String) {
        val media = temporary.resolve("fixture.png").also { Files.write(it, png(1, 1)) }
        val name = UUID.randomUUID().toString()
        val artifact = Path.of(".revyl-maestro", "$name.mp4")
        try {
            LoopbackBackend(platform).use { backend ->
                advertise(backend)
                backend.intercept = { request ->
                    if (!request.path.endsWith("/$route")) null
                    else if (payload == "disconnect") Reply(bytes = byteArrayOf(), disconnect = true)
                    else Reply(bytes = payload.toByteArray())
                }
                val (status, output) = run(backend, command.replace("MEDIA", media.fileName.toString()).replace("RECORDING", name) + "\n- setDarkMode: disabled\n- pressKey: Enter")
                assertEquals(1, status, "$route $payload: $output")
                assertEquals(listOf(route), backend.mutations.map { it.path.substringAfterLast('/') })
                assertFalse(output.contains(PRIVATE_SENTINEL))
            }
        } finally { Files.deleteIfExists(artifact) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `a second media failure prevents remaining media and later commands`(platform: String) {
        val media = (1..3).map { temporary.resolve("$it.png").also { path -> Files.write(path, png(1, 1)) } }
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            backend.intercept = { request ->
                if (request.path.endsWith("/add_media") && json.readTree(request.body).path("name").textValue() == "2.png") Reply(503, PRIVATE_SENTINEL.toByteArray()) else null
            }
            assertEquals(1, run(backend, "- addMedia: [${media.joinToString { it.fileName.toString() }}]\n- pressKey: Enter").first)
            assertEquals(listOf("1.png", "2.png"), backend.mutations.map { json.readTree(it.body).path("name").textValue() })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-id", "invalid-video", "wrong-content-type"])
    fun `malformed recording responses stop later commands without duplicate close requests`(kind: String) {
        val name = UUID.randomUUID().toString()
        val artifact = Path.of(".revyl-maestro", "$name.mp4")
        try {
            LoopbackBackend().use { backend ->
                advertise(backend)
                backend.intercept = { request ->
                    when {
                        kind == "missing-id" && request.path.endsWith("/start_screen_recording") -> Reply(bytes = "{\"success\":true,\"action\":\"start_screen_recording\"}".toByteArray())
                        kind == "invalid-video" && request.path.endsWith("/stop_screen_recording") -> Reply(bytes = PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Content-Type" to "video/mp4"))
                        kind == "wrong-content-type" && request.path.endsWith("/stop_screen_recording") -> Reply(bytes = MP4_FIXTURE, headers = mapOf("Content-Type" to "application/json"))
                        else -> null
                    }
                }
                val (status, output) = run(backend, "- startRecording: $name\n- stopRecording\n- pressKey: Enter")
                assertEquals(1, status, output)
                assertEquals(if (kind == "missing-id") listOf("start_screen_recording") else listOf("start_screen_recording", "stop_screen_recording"), backend.mutations.map { it.path.substringAfterLast('/') })
                assertEquals(0, Files.size(artifact))
                assertFalse(output.contains(PRIVATE_SENTINEL))
            }
        } finally { Files.deleteIfExists(artifact) }
    }

    @Test fun `media validates every file and its size before any earlier action`() {
        val valid = temporary.resolve("valid.png").also { Files.write(it, png(1, 1)) }
        val maximum = temporary.resolve("maximum.mp4").also { RandomAccessFile(it.toFile(), "rw").use { file -> file.setLength(25L * 1024 * 1024) } }
        val accepted = flow("- addMedia: [${maximum.fileName}]")
        FlowPreflight.validateFlow(YamlCommandReader.readCommands(accepted), "android", flow = accepted)
        RandomAccessFile(maximum.toFile(), "rw").use { it.setLength(25L * 1024 * 1024 + 1) }
        val empty = temporary.resolve("empty.png").also { Files.createFile(it) }
        val invalidExtension = temporary.resolve("file.txt").also { Files.writeString(it, "fixture") }
        val invalidName = temporary.resolve("bad name.png").also { Files.write(it, png(1, 1)) }
        val directory = temporary.resolve("directory.png").also { Files.createDirectory(it) }
        val symlink = temporary.resolve("link.png").also { Files.createSymbolicLink(it, valid) }
        for (invalid in listOf(maximum, empty, invalidExtension, invalidName, directory, symlink, temporary.resolve("missing.png"))) {
            LoopbackBackend().use { backend ->
                advertise(backend)
                val command = "- addMedia: [${json.writeValueAsString(valid.fileName.toString())}, ${json.writeValueAsString(invalid.fileName.toString())}]"
                assertEquals(1, run(backend, "- pressKey: Enter\n$command").first, invalid.toString())
                assertTrue(backend.requests.isEmpty(), invalid.toString())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `cropped screenshots can serve as same-flow assertion references`(platform: String) {
        val name = UUID.randomUUID().toString()
        val artifact = Path.of(".revyl-maestro", "$name.png")
        try {
            LoopbackBackend(platform).use { backend ->
                val (status, output) = run(backend, "- takeScreenshot: {path: $name, cropOn: {id: continue}}\n- assertScreenshot: {path: $name, cropOn: {id: continue}, thresholdPercentage: 100}\n- pressKey: Enter")
                assertEquals(0, status, output)
                val image = ImageIO.read(artifact.toFile())
                val scale = if (platform == "ios") 3 else 1
                assertEquals(80 * scale, image.width)
                assertEquals(40 * scale, image.height)
                assertPrivate(artifact)
                assertMutations(backend, listOf("key" to mapOf("key" to "ENTER")))
            }
        } finally { Files.deleteIfExists(artifact) }
    }

    @Test fun `external screenshot references are planned privately and cannot escape the workspace`() {
        val reference = temporary.resolve("reference.png").also { Files.write(it, png(100, 200)) }
        val source = flow("- assertScreenshot: reference.png")
        val validated = FlowPreflight.validateFlow(YamlCommandReader.readCommands(source), "android", flow = source)
        val (target, original) = validated.referenceCopies.entries.single()
        assertEquals(reference.toRealPath(), original)
        assertTrue(target.startsWith(Path.of(".revyl-maestro", "references").toAbsolutePath()))
        assertEquals(target.toString(), validated.commands.mapNotNull { it.assertScreenshotCommand }.single().path)
        val outside = temporary.parent.resolve("${UUID.randomUUID()}.png")
        try {
            Files.write(outside, png(1, 1))
            val link = temporary.resolve("outside.png").also { Files.createSymbolicLink(it, outside) }
            for (path in listOf("missing.png", "../${outside.fileName}", link.fileName.toString(), outside.toString())) {
                LoopbackBackend().use { backend ->
                    assertEquals(1, run(backend, "- pressKey: Enter\n- assertScreenshot: ${json.writeValueAsString(path)}").first, path)
                    assertTrue(backend.requests.isEmpty())
                }
            }
        } finally { Files.deleteIfExists(outside) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../escape", "/absolute", "with.png", "with.mp4", "bad name", "a//b"])
    fun `screenshot and recording paths fail before attachment`(path: String) {
        for (command in listOf("takeScreenshot", "startRecording")) {
            LoopbackBackend().use { backend ->
                advertise(backend)
                assertEquals(1, run(backend, "- pressKey: Enter\n- $command: ${json.writeValueAsString(path)}").first)
                assertTrue(backend.requests.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `random text uses the existing viewer path and preserves its requested length`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, "- inputRandomText: {length: 1000}\n- inputRandomNumber: {length: 1}")
            assertEquals(0, status, output)
            assertEquals(listOf(1000, 1), backend.viewer.messages.map { it.path("value").textValue().length })
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- inputRandomText: {length: 0}", "- inputRandomNumber: {length: 1001}",
        "- inputRandomText: {length: 1.5}", "- inputRandomText: {length: '2'}", "- inputRandomText: {length: true}",
        "- inputRandomText: {length: 2, optional: true}", "- inputRandomNumber: {length: 2, unknown: true}",
        "- travel: {points: ['0,0']}", "- travel: {points: ['0,0', '91,0']}",
        "- travel: {points: ['0,0', '0,1'], speed: 0}", "- travel: {points: ['0,0', '0,1'], speed: 10001}",
        "- travel: {points: ['0,0', '0,1'], speed: '100'}", "- travel: {points: ['0,0', '0,1'], speed: 0.1}",
        "- travel: {points: ['0,0', '0,1'], speed: 100, optional: true}",
    ])
    fun `strict random and travel bounds reject coercions and unsafe options before earlier keys`(command: String) {
        LoopbackBackend().use { backend ->
            assertEquals(1, run(backend, "- pressKey: Enter\n$command").first, command)
            assertTrue(backend.requests.isEmpty(), command)
        }
    }

    @Test fun `travel point and expanded command budgets are validated without waiting for travel`() {
        val points = List(20) { "'0,${it * 0.000001}'" }.joinToString()
        val accepted = flow("- travel: {points: [$points], speed: 10000}")
        FlowPreflight.validateFlow(YamlCommandReader.readCommands(accepted), "android", flow = accepted)
        for (body in listOf(
            "- travel: {points: [$points, '0,0.000020'], speed: 10000}",
            List(50) { "- pressKey: Enter" }.joinToString("\n") + "\n- travel: {points: [$points], speed: 10000}",
        )) {
            LoopbackBackend().use { backend ->
                assertEquals(1, run(backend, body).first)
                assertTrue(backend.requests.isEmpty())
            }
        }
    }

    companion object {
        private val capabilities = setOf("supports_keyboard_control", "supports_add_media", "supports_appearance_read", "supports_airplane_mode", "supports_screen_recording")

        @JvmStatic fun capabilityFailures(): Stream<Arguments> = listOf("android", "ios").flatMap { platform ->
            listOf(
                "- hideKeyboard" to "supports_keyboard_control", "- addMedia: [MEDIA]" to "supports_add_media",
                "- toggleDarkMode" to "supports_appearance_read", "- assertDarkMode" to "supports_appearance_read", "- assertLightMode" to "supports_appearance_read",
                "- startRecording: RECORDING\n- stopRecording" to "supports_screen_recording", "- stopRecording" to "supports_screen_recording",
            ).plus(if (platform == "android") listOf("- setAirplaneMode: enabled" to "supports_airplane_mode", "- toggleAirplaneMode" to "supports_airplane_mode") else emptyList()).flatMap { (command, capability) ->
                listOf("missing", "false", "\"true\"", "1", "null").map { Arguments.of(platform, command, capability, it) }
            }
        }.stream()

        @JvmStatic fun malformedReads(): Stream<Arguments> = listOf("android", "ios").flatMap { platform ->
            listOf("- hideKeyboard" to "keyboard", "- toggleDarkMode" to "appearance", "- assertLightMode" to "appearance", "- assertDarkMode" to "appearance")
                .plus(if (platform == "android") listOf("- toggleAirplaneMode" to "airplane_mode") else emptyList()).flatMap { (command, route) ->
                    val field = when (route) { "keyboard" -> "visible"; "airplane_mode" -> "enabled"; else -> "appearance" }
                    val invalid = if (route == "appearance") listOf("\"Dark\"", "\"system\"", "true", "1", "null") else listOf("\"false\"", "0", "null")
                    (listOf("{}", "[]", "invalid-json") + invalid.map { "{\"$field\":$it}" }).map { Arguments.of(platform, command, route, it) }
                }
        }.stream()

        @JvmStatic fun uncertainWrites(): Stream<Arguments> = listOf("android", "ios").flatMap { platform ->
            listOf("hide_keyboard" to "- hideKeyboard", "add_media" to "- addMedia: [MEDIA]", "set_appearance" to "- toggleDarkMode", "start_screen_recording" to "- startRecording: RECORDING")
                .plus(if (platform == "android") listOf("set_airplane_mode" to "- setAirplaneMode: enabled") else emptyList()).flatMap { (route, command) ->
                    listOf("{\"success\":false,\"action\":\"$route\",\"error\":\"$PRIVATE_SENTINEL\"}", "{\"success\":\"true\",\"action\":\"$route\"}", "{\"success\":true,\"action\":\"wrong\"}", "disconnect")
                        .map { Arguments.of(platform, route, command, it) }
                }
        }.stream()
    }
}
