package ai.revyl.maestro

import kotlinx.coroutines.runBlocking
import maestro.KeyCode
import maestro.Maestro
import maestro.Point
import maestro.js.GraalJsEngine
import maestro.orchestra.Orchestra
import maestro.orchestra.SetClipboardCommand
import maestro.orchestra.CopyTextFromCommand
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

class NativeCommandsTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String): Path = temporary.resolve("${UUID.randomUUID()}.yaml").also {
        Files.writeString(it, "appId: com.example\n---\n$body\n")
    }

    private fun run(backend: LoopbackBackend, body: String, environment: Map<String, String> = backend.environment): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow(body).toString()), environment, PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    private fun assertMutations(backend: LoopbackBackend, expected: List<Pair<String, Map<String, Any>>>) {
        assertEquals(expected.map { "/api/v1/execution/device-proxy/$WORKFLOW/${it.first}" }, backend.mutations.map { it.path })
        expected.zip(backend.mutations).forEach { (action, request) ->
            assertEquals(json.valueToTree(action.second), json.readTree(request.body))
        }
        assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `keys and default erasure operate only on current focus`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, "- pressKey: Enter\n- pressKey: Backspace\n- pressKey: Home\n- eraseText: 2\n- eraseText")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("key" to mapOf("key" to "ENTER"), "key" to mapOf("key" to "BACKSPACE"), "go_home" to emptyMap()) + List(52) { "key" to mapOf("key" to "BACKSPACE") })
        }
    }

    @Test fun `Android back command and back key use the same exact route`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, "- back\n- pressKey: Back")
            assertEquals(0, status, output)
            assertMutations(backend, List(2) { "back" to emptyMap() })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["- back", "- pressKey: Back"])
    fun `iOS back is rejected before attachment even after a valid command`(command: String) {
        LoopbackBackend("ios").use { backend ->
            assertEquals(1, run(backend, "- pressKey: Enter\n$command").first)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `settings and plain links preserve exact values without package targeting`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, """
                - setLocation: {latitude: -90, longitude: 180}
                - setLocation: {latitude: 12.5, longitude: -0.125}
                - setDarkMode: enabled
                - setDarkMode: {value: disabled, optional: false}
                - openLink: {link: 'sampleapp://screen/next', autoVerify: false, browser: false}
                - openLink: https://example.com/path?q=value#section
            """.trimIndent())
            assertEquals(0, status, output)
            assertMutations(backend, listOf(
                "set_location" to mapOf("latitude" to -90.0, "longitude" to 180.0),
                "set_location" to mapOf("latitude" to 12.5, "longitude" to -0.125),
                "set_appearance" to mapOf("appearance" to "dark"),
                "set_appearance" to mapOf("appearance" to "light"),
                "open_url" to mapOf("url" to "sampleapp://screen/next"),
                "open_url" to mapOf("url" to "https://example.com/path?q=value#section"),
            ))
            assertFalse(output.contains("sampleapp"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `long press uses native grid and three seconds for selector and point`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, "- longPressOn: Continue\n- longPressOn: {point: '50%, 50%'}\n- longPressOn: {point: '20, 30'}")
            assertEquals(0, status, output)
            assertMutations(backend, listOf(
                "longpress" to mapOf("x" to 50, "y" to 100, "duration_ms" to 3000),
                "longpress" to mapOf("x" to 50, "y" to 100, "duration_ms" to 3000),
                "longpress" to mapOf("x" to 20, "y" to 30, "duration_ms" to 3000),
            ))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `Maestro executes deliberate repeats and double tap as individual acknowledged taps`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, "- tapOn: {id: continue, repeat: 3, delay: 0}\n- doubleTapOn: {point: '20, 30', delay: 0}\n- doubleTapOn: {id: continue}")
            assertEquals(0, status, output)
            assertMutations(backend, List(3) { "tap" to mapOf("x" to 50, "y" to 100) } + List(2) { "tap" to mapOf("x" to 20, "y" to 30) } + List(2) { "tap" to mapOf("x" to 50, "y" to 100) })
        }
    }

    @Test fun `slow tap acknowledgment does not cause adapter replay`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.method == "POST") Reply(bytes = "{\"success\":true,\"action\":\"tap\"}".toByteArray(), delayMs = 150) else null }
            val start = System.nanoTime()
            val (status, output) = run(backend, "- doubleTapOn: {point: '20, 30', delay: 100}")
            assertEquals(0, status, output)
            assertEquals(2, backend.mutations.size)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 300)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `clipboard commands only change genuine Orchestra local clipboard state`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val commands = FlowPreflight.validate(YamlCommandReader.readCommands(flow("- setClipboard: '$PRIVATE_SENTINEL'\n- copyTextFrom: {id: name}")), platform)
            val copiedValues = mutableListOf<String>()
            RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(20)).use { client ->
                client.attach(SESSION, platform)
                val engine = GraalJsEngine(platform = platform)
                val driver = RevylDriver(client, platform)
                try {
                    val result = runBlocking {
                        Orchestra(Maestro(driver), jsEngineFactory = { engine }, onCommandComplete = { _, command ->
                            if (command.asCommand() is SetClipboardCommand || command.asCommand() is CopyTextFromCommand) {
                                copiedValues += engine.evaluateScript("maestro.copiedText").asString()
                            }
                        }).runFlow(commands)
                    }
                    assertTrue(result.success)
                } finally { driver.close(); engine.close() }
            }
            assertEquals(listOf(PRIVATE_SENTINEL, "Existing name"), copiedValues)
            assertTrue(backend.mutations.isEmpty())
            assertFalse(backend.requests.any { it.body.contains(PRIVATE_SENTINEL) })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- pasteText", "- inputText: ''", "- stopApp", "- killApp", "- clearState", "- clearKeychain",
        "- swipe: {start: '10, 10', end: '20, 20', duration: 0}", "- swipe: {direction: UP, duration: 10001}", "- scrollUntilVisible: {element: {text: Continue}}",
        "- hideKeyboard", "- pressKey: Tab", "- pressKey: Power", "- pressKey: 'Volume Up'", "- pressKey: Lock",
        "- eraseText: 0", "- eraseText: -1", "- eraseText: 101", "- eraseText: 2147483647", "- eraseText: 1.5",
        "- tapOn: {text: Continue, repeat: 0}", "- tapOn: {text: Continue, repeat: -1}", "- tapOn: {text: Continue, repeat: 101}",
        "- tapOn: {text: Continue, repeat: 2, delay: -1}", "- tapOn: {text: Continue, repeat: 2, delay: 10001}",
        "- tapOn: {text: Continue, repeat: 100, delay: 10000}", "- tapOn: {text: Continue, repeat: 2, delay: 9223372036854775807}",
        "- tapOn: {text: Continue, repeat: 1.5}", "- tapOn: {text: Continue, repeat: 2, delay: 1.5}",
        "- tapOn: {text: Continue, delay: 10}", "- doubleTapOn: {text: Continue, repeat: 3}",
        "- longPressOn: {text: Continue, repeat: 2}", "- longPressOn: {text: Continue, delay: 100}",
        "- longPressOn: {text: Continue, retryTapIfNoChange: true}", "- longPressOn: {text: Continue, waitUntilVisible: true}",
        "- longPressOn: {text: Continue, point: '10%, 20%'}", "- doubleTapOn: {text: Continue, optional: true}",
        "- longPressOn: {text: Continue, start: '10, 20'}", "- longPressOn: {text: Continue, end: '10, 20'}",
        "- tapOn: {text: Continue, repeat: 2, below: {text: Name, repeat: 3}}",
        "- tapOn: {point: '20, 30', repeat: 2, waitToSettleTimeoutMs: 120001}",
        "- tapOn: {point: '50%, 50%', repeat: 2, retryTapIfNoChange: true}",
        "- setClipboard: {text: hello, optional: true}", "- setClipboard: '\${1 + 1}'", "- copyTextFrom: {text: '\${1 + 1}'}",
        "- copyTextFrom: {text: Continue, optional: true}", "- copyTextFrom: {css: button}", "- copyTextFrom: {text: Continue, repeat: 2}",
        "- copyTextFrom: {text: Continue, point: '10, 20'}", "- copyTextFrom: {text: Continue, waitUntilVisible: true}",
        "- copyTextFrom: {text: Continue, below: {text: Name, point: '10, 20'}}",
        "- setLocation: {latitude: NaN, longitude: 0}", "- setLocation: {latitude: 0, longitude: Infinity}",
        "- setLocation: {latitude: 91, longitude: 0}", "- setLocation: {latitude: -91, longitude: 0}",
        "- setLocation: {latitude: 0, longitude: 181}", "- setLocation: {latitude: 0, longitude: -181}",
        "- setLocation: {latitude: '\${1}', longitude: 0}", "- setLocation: {latitude: 0, longitude: 0, optional: true}",
        "- setDarkMode: {value: enabled, optional: true}", "- setDarkMode: {value: enabled, unknown: true}",
        "- setDarkMode: not-enabled", "- setDarkMode: {value: enabled, label: '\${1}'}",
        "- toggleDarkMode", "- assertDarkMode", "- assertLightMode", "- setOrientation: PORTRAIT",
        "- setAirplaneMode: enabled", "- toggleAirplaneMode", "- startRecording: capture", "- stopRecording",
        "- openLink: {link: 'https://example.com', autoVerify: true}", "- openLink: {link: 'https://example.com', browser: true}",
        "- openLink: {link: 'https://example.com', optional: true}", "- openLink: '\${1}'", "- openLink: /relative",
        "- openLink: 'https://user:password@example.com'", "- openLink: 'javascript:alert(1)'", "- openLink: 'data:text/plain,x'",
        "- openLink: 'file:///etc/passwd'", "- openLink: 'intent://screen'", "- openLink: 'https:/missing-host'",
        "- openLink: \"https://example.com/\\nheader\"",
        "- retry: {maxRetries: 2, commands: [{pressKey: Enter}]}", "- repeat: {times: 2, commands: [{pressKey: Enter}]}",
        "- assertWithAI: anything", "- extractTextWithAI: anything", "- tapOn: {css: button}",
    ])
    fun `unsupported options and malformed bounds fail before any earlier valid mutation`(command: String) {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, "- pressKey: Enter\n$command")
            assertEquals(1, status, command)
            assertTrue(backend.requests.isEmpty(), command)
            assertFalse(output.contains(PRIVATE_SENTINEL))
            assertFalse(output.contains(FIXTURE_KEY))
        }
    }

    @Test fun `expanded command and text size budgets include parsed repeats and default erasure`() {
        val accepted = listOf(
            "- tapOn: {point: '20, 30', repeat: 100, delay: 0}",
            "- tapOn: {point: '20, 30', repeat: 12, delay: 10000}",
            "- eraseText: 100", List(20) { "- eraseText" }.joinToString("\n"),
            List(999) { "- setClipboard: x" }.joinToString("\n"),
            "- setClipboard: '${"x".repeat(MAX_CLIPBOARD_BYTES)}'",
        )
        accepted.forEach { FlowPreflight.validate(YamlCommandReader.readCommands(flow(it)), "android") }
        val rejected = listOf(
            List(21) { "- eraseText" }.joinToString("\n"),
            List(11) { "- tapOn: {point: '20, 30', repeat: 100, delay: 0}" }.joinToString("\n"),
            List(1000) { "- setClipboard: x" }.joinToString("\n"),
            "- setClipboard: '${"x".repeat(MAX_CLIPBOARD_BYTES + 1)}'",
            "- setClipboard: '${"é".repeat(MAX_CLIPBOARD_BYTES / 2 + 1)}'",
            "- openLink: 'https://example.com/${"x".repeat(8192)}'",
        )
        rejected.forEach { body ->
            LoopbackBackend().use { backend ->
                assertEquals(1, run(backend, body).first)
                assertTrue(backend.requests.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @MethodSource("uncertainSequences")
    fun `uncertain sequence result is terminal at every position without teardown`(platform: String, sequence: String, failurePosition: Int, fault: String) {
        LoopbackBackend(platform).use { backend ->
            backend.intercept = { request ->
                if (request.method != "POST" || backend.mutations.size != failurePosition) null else when (fault) {
                    "false" -> Reply(bytes = "{\"success\":false,\"action\":\"${request.path.substringAfterLast('/')}\",\"error\":\"$PRIVATE_SENTINEL\"}".toByteArray())
                    "wrong_action" -> Reply(bytes = "{\"success\":true,\"action\":\"launch\"}".toByteArray())
                    "disconnect" -> Reply(bytes = byteArrayOf(), disconnect = true)
                    "timeout" -> Reply(bytes = "{\"success\":true}".toByteArray(), delayMs = 1000)
                    else -> Reply(429, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Retry-After" to "0"))
                }
            }
            val body = when (sequence) {
                "tap" -> "- tapOn: {point: '20, 30', repeat: 3, delay: 0}"
                "double" -> "- doubleTapOn: {point: '20, 30', delay: 0}"
                else -> "- eraseText: 3"
            }
            val (status, output) = run(backend, "$body\n- pressKey: Home", backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to "500"))
            assertEquals(1, status)
            assertEquals(failurePosition, backend.mutations.size)
            assertTrue(backend.mutations.all { it.path.endsWith(if (sequence == "erase") "/key" else "/tap") })
            assertFalse(output.contains(PRIVATE_SENTINEL))
            assertFalse(output.contains(FIXTURE_KEY))
        }
    }

    @Test fun `longpress route name is not accepted as its canonical action acknowledgment`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path.endsWith("/longpress")) Reply(bytes = "{\"success\":true,\"action\":\"longpress\"}".toByteArray()) else null }
            assertEquals(1, run(backend, "- longPressOn: Continue\n- pressKey: Home").first)
            assertEquals(1, backend.mutations.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `installed CLI executes the added native command slice with a scrubbed environment`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val body = "- pressKey: Enter\n- eraseText: 2\n- setClipboard: '$PRIVATE_SENTINEL'\n- copyTextFrom: {id: name}\n- longPressOn: Continue\n- tapOn: {point: '20, 30', repeat: 2, delay: 0}\n- setLocation: {latitude: 0, longitude: 0}\n- setDarkMode: disabled\n- openLink: https://example.com"
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", platform, flow(body).toString()).directory(temporary.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temporary.toString(), "JAVA_HOME" to System.getProperty("java.home")) + backend.environment)
            val process = builder.start()
            try {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Installed CLI did not exit within its deadline")
                val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                assertEquals(0, process.exitValue(), output)
                assertEquals(listOf("key", "key", "key", "longpress", "tap", "tap", "set_location", "set_appearance", "open_url"), backend.mutations.map { it.path.substringAfterLast('/') })
                assertFalse(output.contains(PRIVATE_SENTINEL))
                assertFalse(output.contains(FIXTURE_KEY))
            } finally { process.destroyForcibly() }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["longpress", "key", "go_home", "back", "set_location", "set_appearance", "open_url"])
    fun `each new action requires exact server confirmation and stops later commands`(action: String) {
        val command = mapOf("longpress" to "longPressOn: Continue", "key" to "pressKey: Enter", "go_home" to "pressKey: Home", "back" to "back", "set_location" to "setLocation: {latitude: 0, longitude: 0}", "set_appearance" to "setDarkMode: enabled", "open_url" to "openLink: https://example.com").getValue(action)
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.method == "POST") Reply(bytes = "{\"success\":true,\"action\":\"tap\"}".toByteArray()) else null }
            assertEquals(1, run(backend, "- $command\n- pressKey: Enter").first)
            assertEquals(listOf("/api/v1/execution/device-proxy/$WORKFLOW/$action"), backend.mutations.map { it.path })
        }
    }

    @Test fun `direct driver calls validate bounds and platform without bypassing the terminal latch`() {
        val invalidActions = listOf<(RevylDriver) -> Unit>(
            { it.eraseText(0) }, { it.eraseText(101) }, { it.pressKey(KeyCode.BACK) }, { it.pressKey(KeyCode.TAB) },
            { it.setLocation(Double.NaN, 0.0) }, { it.setLocation(0.0, Double.POSITIVE_INFINITY) },
            { it.openLink("/relative", null, false, false) }, { it.openLink("https://example.com", null, true, false) },
            { it.longPress(Point(100, 0)) }, { it.longPress(Point(-1, 0)) },
        )
        invalidActions.forEach { action ->
            LoopbackBackend("ios").use { backend ->
                RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).use { client ->
                    client.attach(SESSION, "ios")
                    val driver = RevylDriver(client, "ios")
                    assertFailsWith<AdapterFailure> { action(driver) }
                    assertFailsWith<AdapterFailure> { driver.pressKey(KeyCode.HOME) }
                    driver.close()
                    assertTrue(backend.mutations.isEmpty())
                }
            }
        }
    }

    companion object {
        @JvmStatic fun uncertainSequences(): Stream<Arguments> = listOf("android", "ios").flatMap { platform ->
            listOf("tap", "double", "erase").flatMap { sequence ->
                (1..if (sequence == "double") 2 else 3).flatMap { position ->
                    listOf("false", "wrong_action", "disconnect", "timeout", "http").map { fault -> Arguments.of(platform, sequence, position, fault) }
                }
            }
        }.stream()
    }
}
