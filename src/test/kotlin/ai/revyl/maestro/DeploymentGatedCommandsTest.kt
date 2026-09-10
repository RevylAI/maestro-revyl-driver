package ai.revyl.maestro

import maestro.Point
import maestro.orchestra.yaml.YamlCommandReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentGatedCommandsTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String): Path = temporary.resolve("${UUID.randomUUID()}.yaml").also {
        Files.writeString(it, "appId: com.example\n---\n$body\n")
    }

    private fun run(backend: LoopbackBackend, body: String, environment: Map<String, String> = backend.environment): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow(body).toString()), environment, PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    private fun advertise(backend: LoopbackBackend) {
        backend.healthOverrides = mapOf("supports_focused_text_input" to true, "supports_explicit_drag_duration" to true)
    }

    @Test fun `Android text and local clipboard paste preserve the exact focus-only payload`() {
        LoopbackBackend().use { backend ->
            advertise(backend)
            val text = "Montréal 😀\tline\nnext\r"
            val (status, output) = run(backend, """
                - pressKey: Enter
                - inputText: ${json.writeValueAsString(text)}
                - inputText: {text: 'second input', optional: false}
                - setClipboard: clipboard value
                - pasteText
                - copyTextFrom: {id: name}
                - pasteText
            """.trimIndent())
            assertEquals(0, status, output)
            assertEquals(listOf("key", "text-input", "text-input", "text-input", "text-input"), backend.mutations.map { it.path.substringAfterLast('/') })
            assertEquals(listOf(text, "second input", "clipboard value", "Existing name"), backend.mutations.drop(1).map {
                val payload = json.readTree(it.body)
                assertEquals(setOf("text"), payload.fieldNames().asSequence().toSet())
                payload.path("text").textValue()
            })
            assertEquals("/api/v1/execution/device-proxy/$WORKFLOW/health", backend.requests[1].path)
            assertEquals(1, backend.requests.count { it.path.endsWith("/health") })
            assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
            assertFalse(backend.requests.any { it.path.endsWith("/input") || it.path.endsWith("/tap") })
            assertFalse(output.contains("Montréal"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `swipe forms and scroll preserve native endpoint geometry and duration through drag`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            advertise(backend)
            val (status, output) = run(backend, """
                - swipe: {start: '10, 20', end: '80, 150', duration: 1234}
                - swipe: {start: '20%, 80%', end: '90%, 10%', duration: 2345}
                - swipe: {direction: UP}
                - swipe: {direction: DOWN, duration: 500}
                - swipe: {direction: LEFT, duration: 600}
                - swipe: {direction: RIGHT, duration: 700}
                - swipe: {direction: UP, from: {id: continue}, duration: 800}
                - scroll
            """.trimIndent())
            assertEquals(0, status, output)
            val expected = listOf(
                listOf(10, 20, 80, 150, 1234), listOf(20, 160, 90, 20, 2345),
                listOf(50, if (platform == "ios") 180 else 100, 50, 20, 400),
                listOf(50, 40, 50, 180, 500), listOf(90, 100, 10, 100, 600), listOf(10, 100, 90, 100, 700),
                listOf(50, 100, 50, 20, 800), listOf(50, 100, 50, 20, if (platform == "ios") 333 else 400),
            )
            assertEquals(expected.size, backend.mutations.size)
            backend.mutations.zip(expected).forEach { (request, values) ->
                assertEquals("/api/v1/execution/device-proxy/$WORKFLOW/drag", request.path)
                val payload = json.readTree(request.body)
                assertEquals(setOf("start_x", "start_y", "end_x", "end_y", "duration_ms"), payload.fieldNames().asSequence().toSet())
                assertEquals(values, listOf("start_x", "start_y", "end_x", "end_y", "duration_ms").map { payload.path(it).intValue() })
            }
            assertFalse(backend.requests.any { it.path.endsWith("/swipe") })
            assertEquals("/api/v1/execution/device-proxy/$WORKFLOW/health", backend.requests[1].path)
        }
    }

    @ParameterizedTest
    @MethodSource("missingCapabilities")
    fun `missing false or malformed capabilities stop the whole flow before earlier mutations`(command: String, field: String, value: String) {
        LoopbackBackend().use { backend ->
            if (value != "missing") backend.healthOverrides = mapOf(field to json.readValue(value, Any::class.java))
            val (status, output) = run(backend, "- pressKey: Home\n$command")
            assertEquals(1, status)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(listOf("/api/v1/execution/device-sessions/$SESSION", "/api/v1/execution/device-proxy/$WORKFLOW/health"), backend.requests.map { it.path })
            assertFalse(output.contains(PRIVATE_SENTINEL))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{\"status\":\"stopped\"}", "{\"workflow_run_id\":\"11111111-1111-4111-8111-111111111111\"}",
        "{\"workflow_run_id\":null}", "{\"platform\":\"ios\"}", "{\"platform\":null}",
        "{\"device_connected\":false}", "{\"device_connected\":\"true\"}", "{\"device_connected\":1}",
    ])
    fun `capabilities cannot authorize a different unhealthy or ambiguous device`(overrides: String) {
        LoopbackBackend().use { backend ->
            advertise(backend)
            val invalid = json.readTree(overrides).fields().asSequence().associate { it.key to json.treeToValue(it.value, Any::class.java) }
            backend.healthOverrides += invalid
            assertEquals(1, run(backend, "- pressKey: Home\n- inputText: hello").first)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `every required capability is checked before a mixed flow begins`() {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_focused_text_input" to true)
            assertEquals(1, run(backend, "- inputText: hello\n- scroll").first)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `current-runtime commands do not require future capability fields`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, "- pressKey: Enter\n- setClipboard: hello\n- copyTextFrom: {id: name}\n- longPressOn: Continue")
            assertEquals(0, status, output)
            assertFalse(backend.requests.any { it.path.endsWith("/health") })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["- inputText: hello", "- setClipboard: hello\n- pasteText", "- copyTextFrom: {id: name}\n- pasteText"])
    fun `iOS text operations fail preflight even if a worker advertises support`(body: String) {
        LoopbackBackend("ios").use { backend ->
            advertise(backend)
            assertEquals(1, run(backend, "- pressKey: Home\n$body").first)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- inputText: ''", "- inputText: {text: hello, optional: true}", "- inputText: {text: hello, unknown: true}",
        "- inputText: {text: hello, label: '\${1}'}", "- inputText: '\${1}'", "- inputText: 123", "- inputText: {text: 123}",
        "- inputText: \"invalid\\u0000text\"", "- inputText: \"invalid\\u007ftext\"", "- inputText: \"invalid\\u0085text\"",
        "- inputText: \"invalid\\ud800text\"", "- inputText: \"invalid\\udc00text\"",
        "- pasteText", "- pasteText\n- setClipboard: later", "- setClipboard: ''\n- pasteText",
        "- setClipboard: \"invalid\\u0000text\"\n- pasteText", "- setClipboard: hello\n- pasteText: {optional: true}",
        "- swipe: {direction: UP, duration: 0}", "- swipe: {direction: UP, duration: -1}", "- swipe: {direction: UP, duration: 10001}",
        "- swipe: {direction: UP, duration: 1.5}", "- swipe: {direction: UP, duration: '500'}", "- swipe: {direction: UP, unknown: true}",
        "- swipe: {direction: UP, optional: true}", "- swipe: {direction: UP, optional: invalid}",
        "- swipe: {direction: UP, waitToSettleTimeoutMs: invalid}", "- swipe: {direction: UP, waitToSettleTimeoutMs: 0}",
        "- swipe: {direction: UP, from: {id: continue, point: '50%, 50%'}}", "- swipe: {direction: UP, from: {id: continue, repeat: 2}}",
        "- swipe: {direction: UP, from: {id: continue, retryTapIfNoChange: true}}", "- swipe: {direction: UP, from: {css: button}}",
        "- swipe: {start: '10, 20', end: '20, 30', from: {id: continue}}", "- swipe: {start: '10, 20'}",
        "- swipe: {start: '10, 20', end: '10, 20'}", "- swipe: {start: '10, 20', end: '10, 20, 30'}",
        "- swipe: {start: '-1, 20', end: '10, 20'}", "- swipe: {start: '16000, 20', end: '10, 20'}",
        "- swipe: {start: '10%, 20%', end: '10%, 20%'}", "- swipe: {start: '100%, 20%', end: '10%, 20%'}",
        "- swipe: {start: '10%, 20%', end: '10, 20'}", "- swipe: {start: '\${1}, 20', end: '10, 20'}",
        "- swipe: {start: '10, 20', end: '10, 30', direction: UP}", "- scroll: {optional: true}",
        "- scrollUntilVisible: {element: {text: Continue}}",
    ])
    fun `unsupported input paste and swipe options fail before any attachment`(command: String) {
        LoopbackBackend().use { backend ->
            advertise(backend)
            assertEquals(1, run(backend, "- pressKey: Enter\n$command").first, command)
            assertTrue(backend.requests.isEmpty(), command)
        }
    }

    @Test fun `text validation bounds UTF-8 and validates Unicode without changing content`() {
        listOf(" ", "\t\r\n", "é😀", "x".repeat(16384), "😀".repeat(4096)).forEach(::requireFocusedText)
        listOf("", "x".repeat(16385), "é".repeat(8193), "😀".repeat(4097), "\uD800", "\uDC00", "\u0001", "\u009f").forEach {
            assertFailsWith<AdapterFailure> { requireFocusedText(it) }
        }
        val oversized = "é".repeat(8193)
        LoopbackBackend().use { backend ->
            advertise(backend)
            assertEquals(1, run(backend, "- inputText: '$oversized'").first)
            assertTrue(backend.requests.isEmpty())
        }
        listOf("- inputText: '${"x".repeat(16384)}'", "- swipe: {direction: UP, duration: 1}", "- swipe: {direction: UP, duration: 10000}").forEach {
            FlowPreflight.validate(YamlCommandReader.readCommands(flow(it)), "android")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "oversized", "control", "expression"])
    fun `dynamic copied text is validated before paste can mutate focus`(kind: String) {
        LoopbackBackend().use { backend ->
            advertise(backend)
            val copiedText = when (kind) {
                "empty" -> ""
                "oversized" -> PRIVATE_SENTINEL.repeat(1000)
                "control" -> "$PRIVATE_SENTINEL&#127;"
                else -> "\${'$PRIVATE_SENTINEL'}"
            }
            backend.intercept = { request ->
                if (request.path.endsWith("/hierarchy")) Reply(bytes = backend.hierarchy().toString(Charsets.UTF_8).replace("Existing name", copiedText).toByteArray()) else null
            }
            val (status, output) = run(backend, "- copyTextFrom: {id: name}\n- pasteText\n- pressKey: Home")
            assertEquals(1, status)
            assertTrue(backend.requests.any { it.path.endsWith("/hierarchy") })
            assertTrue(backend.mutations.isEmpty())
            assertFalse(output.contains(PRIVATE_SENTINEL))
        }
    }

    @Test fun `gated commands count toward the existing expanded flow bound`() {
        LoopbackBackend().use { backend ->
            advertise(backend)
            val repeatedTaps = List(10) { "- tapOn: {point: '20, 30', repeat: 100, delay: 0}" }.joinToString("\n")
            for (command in listOf("- inputText: hello", "- scroll", "- swipe: {direction: UP}")) {
                assertEquals(1, run(backend, "$repeatedTaps\n$command").first)
            }
            assertTrue(backend.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `installed CLI enforces deployment gates and executes only advertised native operations`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            val body = (if (platform == "android") "- inputText: '$PRIVATE_SENTINEL'\n- setClipboard: hello\n- pasteText\n" else "") +
                "- swipe: {start: '10, 20', end: '80, 150', duration: 1234}\n- scroll"
            for (advertised in listOf(false, true)) {
                if (advertised) advertise(backend)
                val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", platform, flow(body).toString()).directory(temporary.toFile()).redirectErrorStream(true)
                builder.environment().clear()
                builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temporary.toString(), "JAVA_HOME" to System.getProperty("java.home")) + backend.environment)
                val process = builder.start()
                try {
                    assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Installed CLI did not exit within its deadline")
                    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                    assertEquals(if (advertised) 0 else 1, process.exitValue(), output)
                    val expected = if (!advertised) emptyList() else (if (platform == "android") listOf("text-input", "text-input") else emptyList()) + listOf("drag", "drag")
                    assertEquals(expected, backend.mutations.map { it.path.substringAfterLast('/') })
                    assertFalse(output.contains(PRIVATE_SENTINEL))
                    assertFalse(output.contains(FIXTURE_KEY))
                } finally { process.destroyForcibly() }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("uncertainOperations")
    fun `gated operation failure or timeout cannot replay or reach later commands`(operation: String, fault: String) {
        LoopbackBackend().use { backend ->
            advertise(backend)
            backend.intercept = { request ->
                if (!request.path.endsWith("/$operation")) null else when (fault) {
                    "false" -> Reply(bytes = "{\"success\":false,\"action\":\"input\",\"error\":\"$PRIVATE_SENTINEL\"}".toByteArray())
                    "mismatch" -> Reply(bytes = "{\"success\":true,\"action\":\"text-input\"}".toByteArray())
                    "missing" -> Reply(bytes = "{\"success\":true}".toByteArray())
                    "string_success" -> Reply(bytes = json.writeValueAsBytes(mapOf("success" to "true", "action" to if (operation == "text-input") "input" else "drag")))
                    "error" -> Reply(bytes = "{\"success\":true,\"action\":\"${if (operation == "text-input") "input" else "drag"}\",\"error\":\"$PRIVATE_SENTINEL\"}".toByteArray())
                    "invalid" -> Reply(bytes = PRIVATE_SENTINEL.toByteArray())
                    "disconnect" -> Reply(bytes = byteArrayOf(), disconnect = true)
                    "timeout" -> Reply(bytes = "{}".toByteArray(), delayMs = 1000)
                    else -> Reply(503, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Retry-After" to "0"))
                }
            }
            val command = if (operation == "text-input") "- setClipboard: hello\n- pasteText" else "- swipe: {direction: UP, duration: 1234}"
            val (status, output) = run(backend, "$command\n- pressKey: Home", backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to "500"))
            assertEquals(1, status)
            assertEquals(listOf("/api/v1/execution/device-proxy/$WORKFLOW/$operation"), backend.mutations.map { it.path })
            assertFalse(output.contains(PRIVATE_SENTINEL))
            assertFalse(output.contains(FIXTURE_KEY))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid", "disconnect", "timeout", "http", "duplicate", "trailing"])
    fun `health transport failures remain terminal before every mutation`(fault: String) {
        LoopbackBackend().use { backend ->
            advertise(backend)
            backend.intercept = { if (!it.path.endsWith("/health")) null else when (fault) {
                "invalid" -> Reply(bytes = "[]".toByteArray())
                "disconnect" -> Reply(bytes = byteArrayOf(), disconnect = true)
                "timeout" -> Reply(bytes = backend.health(), delayMs = 1000)
                "duplicate" -> Reply(bytes = backend.health().toString(Charsets.UTF_8).replace("\"supports_explicit_drag_duration\":true", "\"supports_explicit_drag_duration\":false,\"supports_explicit_drag_duration\":true").toByteArray())
                "trailing" -> Reply(bytes = backend.health() + " []".toByteArray())
                else -> Reply(503, PRIVATE_SENTINEL.toByteArray())
            } }
            assertEquals(1, run(backend, "- pressKey: Home\n- scroll", backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to "500")).first)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(2, backend.requests.size)
        }
    }

    @Test fun `driver cannot bypass capability verification or native bounds`() {
        LoopbackBackend().use { backend ->
            advertise(backend)
            RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).use { client ->
                client.attach(SESSION, "android")
                assertFailsWith<AdapterFailure> { RevylDriver(client, "android").inputText("hello") }
                assertTrue(backend.mutations.isEmpty())
                assertEquals(1, backend.requests.size)
            }
            RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).use { client ->
                client.attach(SESSION, "android")
                client.verifyCapabilities(setOf(WorkerCapability.EXPLICIT_DRAG_DURATION))
                val driver = RevylDriver(client, "android")
                assertFailsWith<AdapterFailure> { driver.swipe(Point(100, 0), Point(10, 20), 500) }
                assertFailsWith<AdapterFailure> { driver.swipe(Point(0, 0), Point(10, 20), 500) }
                assertTrue(backend.mutations.isEmpty())
            }
        }
    }

    companion object {
        @JvmStatic fun missingCapabilities(): Stream<Arguments> = listOf(
            "- inputText: hello" to "supports_focused_text_input",
            "- setClipboard: hello\n- pasteText" to "supports_focused_text_input",
            "- swipe: {direction: UP}" to "supports_explicit_drag_duration",
            "- scroll" to "supports_explicit_drag_duration",
        ).flatMap { (command, field) ->
            listOf("missing", "false", "null", "1", "\"true\"", "[]", "{}").map { Arguments.of(command, field, it) }
        }.stream()

        @JvmStatic fun uncertainOperations(): Stream<Arguments> = listOf("text-input", "drag").flatMap { operation ->
            listOf("false", "mismatch", "missing", "string_success", "error", "invalid", "disconnect", "timeout", "http").map { Arguments.of(operation, it) }
        }.stream()
    }
}
