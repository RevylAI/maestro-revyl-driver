package ai.revyl.maestro

import maestro.js.GraalJsEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchestraIntegrationTest {
    @TempDir lateinit var temporary: Path

    private fun run(backend: LoopbackBackend, body: String, config: String = "appId: com.example", environment: Map<String, String> = backend.environment): Pair<Int, String> {
        val flow = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(flow, "$config\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow.toString()), environment, PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `real YAML and Orchestra execute native assertions one tap and private screenshot`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val name = "test-${UUID.randomUUID()}"
            val screenshot = Path.of(".revyl-maestro/$name.png")
            try {
                val (status, output) = run(backend, """
                    - assertVisible: Continue
                    - assertVisible:
                        id: name
                        text: Existing name
                        focused: true
                    - tapOn:
                        id: continue
                    - assertVisible: Welcome
                    - assertNotVisible: Continue
                    - takeScreenshot: $name
                """.trimIndent())
                assertEquals(0, status, output)
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot)))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot.parent)))
                assertEquals(1, backend.mutations.size)
                assertEquals(json.readTree("{\"x\":50,\"y\":100}"), json.readTree(backend.mutations.single().body))
                assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
                assertFalse(output.contains("Continue"))
            } finally { Files.deleteIfExists(screenshot) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `native extended wait and screen wait only read`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, """
                - extendedWaitUntil:
                    visible: Continue
                    timeout: 500
                - waitForAnimationToEnd:
                    timeout: 500
                - assertNotVisible: Missing
            """.trimIndent())
            assertEquals(0, status, output)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `failed real assertion is nonzero and does not execute later taps`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, """
                - extendedWaitUntil:
                    visible: $PRIVATE_SENTINEL
                    timeout: 100
                - tapOn: Continue
            """.trimIndent())
            assertEquals(1, status)
            assertTrue(backend.mutations.isEmpty())
            assertFalse(output.contains(PRIVATE_SENTINEL))
            assertFalse(output.contains(FIXTURE_KEY))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- inputText: secret",
        "- stopApp",
        "- killApp",
        "- clearState",
        "- clearKeychain",
        "- scroll",
        "- swipe: {direction: UP}",
        "- hideKeyboard",
        "- pressKey: Enter",
        "- eraseText",
        "- openLink: https://example.com",
        "- inputRandomText",
        "- doubleTapOn: Continue",
        "- longPressOn: Continue",
        "- tapOn: {text: Continue, repeat: 2}",
        "- tapOn: {text: Continue, retryTapIfNoChange: true}",
        "- tapOn: {text: Continue, waitUntilVisible: true}",
        "- tapOn: {text: Missing, optional: true}",
        "- assertVisible: {text: Missing, optional: true}",
        "- retry: {maxRetries: 2, commands: [{tapOn: Continue}]}",
        "- repeat: {times: 2, commands: [{tapOn: Continue}]}",
        "- runFlow: {commands: [{tapOn: Continue}]}",
        "- evalScript: \"\${1 + 1}\"",
        "- assertTrue: \"\${true}\"",
        "- tapOn: {text: '\${1 + 1}'}",
        "- takeScreenshot: ../escape",
        "- takeScreenshot: {path: smoke, cropOn: {text: Continue}}",
        "- launchApp",
        "- launchApp: {stopApp: false}",
        "- launchApp: {stopApp: false, permissions: {all: allow}}",
        "- launchApp: {stopApp: false, permissions: {}, clearState: true}",
        "- launchApp: {stopApp: false, permissions: {}, clearState: false}",
        "- launchApp: {stopApp: false, permissions: {}, clearKeychain: true}",
        "- launchApp: {stopApp: false, permissions: {}, launchArguments: {name: value}}",
        "- launchApp: {stopApp: false, permissions: {}, launchArguments: {}}",
        "- setPermissions: {permissions: {all: allow}}",
        "- setLocation: {latitude: 1, longitude: 2}",
        "- takeScreenshot: '\${1 + 1}'",
    ])
    fun `unsupported flow preflight prevents every request including prior valid taps`(unsupported: String) {
        LoopbackBackend().use { backend ->
            val (status, _) = run(backend, "- tapOn: Continue\n$unsupported")
            assertEquals(1, status, unsupported)
            assertTrue(backend.requests.isEmpty(), unsupported)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "onFlowStart: [{tapOn: Continue}]",
        "onFlowComplete: [{inputText: secret}]",
        "env: {REVYL_API_KEY: secret}",
        "androidWebViewHierarchy: devtools",
        "properties: {foo: bar}",
        "apiKey: secret",
    ])
    fun `config hooks environment and unknown capabilities fail before attach`(config: String) {
        LoopbackBackend().use { backend ->
            assertEquals(1, run(backend, "- tapOn: Continue", "appId: com.example\n$config").first)
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test fun `nonresetting launch uses only launch endpoint`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, "- launchApp: {stopApp: false, permissions: {}}\n- assertVisible: Continue")
            assertEquals(0, status, output)
            assertEquals(1, backend.mutations.size)
            assertTrue(backend.mutations.single().path.endsWith("/launch"))
            assertEquals("{\"bundle_id\":\"com.example\"}", backend.mutations.single().body)
        }
    }

    @Test fun `existing nested flow and script files are rejected before attachment`() {
        val script = temporary.resolve("script.js")
        val nested = temporary.resolve("nested.yaml")
        Files.writeString(script, "throw new Error('$PRIVATE_SENTINEL')")
        Files.writeString(nested, "appId: com.example\n---\n- tapOn: Continue\n- inputText: secret\n")
        for (command in listOf("runScript: ${script.toAbsolutePath()}", "runFlow: ${nested.toAbsolutePath()}")) {
            LoopbackBackend().use { backend ->
                val (status, output) = run(backend, "- tapOn: Continue\n- $command")
                assertEquals(1, status)
                assertTrue(backend.requests.isEmpty())
                assertFalse(output.contains(PRIVATE_SENTINEL))
            }
        }
    }

    @Test fun `point tap uses native grid rather than screenshot pixels`() {
        LoopbackBackend("ios").use { backend ->
            val (status, output) = run(backend, "- tapOn: {point: '50%, 50%'}")
            assertEquals(0, status, output)
            assertEquals(1, backend.mutations.size)
            assertEquals(json.readTree("{\"x\":50,\"y\":100}"), json.readTree(backend.mutations.single().body))
        }
    }

    @Test fun `tap failure is terminal without replay or remote teardown`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { request -> if (request.path.endsWith("/tap")) Reply(503, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Retry-After" to "0")) else null }
            val (status, output) = run(backend, "- tapOn: Continue\n- tapOn: Continue")
            assertEquals(1, status)
            assertEquals(1, backend.mutations.size)
            assertTrue(output.contains("HTTP 503"))
            assertFalse(output.contains(PRIVATE_SENTINEL))
        }
    }

    @Test fun `screenshot read failure swallowed by Maestro is latched before mutation`() {
        LoopbackBackend().use { backend ->
            var screenshots = 0
            backend.intercept = { request -> if (request.path.endsWith("/screenshot") && ++screenshots >= 3) Reply(500, PRIVATE_SENTINEL.toByteArray()) else null }
            val (status, _) = run(backend, "- tapOn: Continue")
            assertEquals(1, status)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(3, screenshots)
        }
    }

    @Test fun `screen wait cannot swallow transport failure into success`() {
        LoopbackBackend().use { backend ->
            var screenshots = 0
            backend.intercept = { request -> if (request.path.endsWith("/screenshot") && ++screenshots >= 2) Reply(500, PRIVATE_SENTINEL.toByteArray()) else null }
            assertEquals(1, run(backend, "- waitForAnimationToEnd: {timeout: 100}").first)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(2, screenshots)
        }
    }

    @Test fun `malformed screenshot swallowed by Maestro cannot permit a tap`() {
        LoopbackBackend().use { backend ->
            var screenshots = 0
            backend.intercept = { request -> if (request.path.endsWith("/screenshot") && ++screenshots >= 3) Reply(bytes = png(100, 200).also { it[32] = (it[32].toInt() xor 1).toByte() }) else null }
            assertEquals(1, run(backend, "- tapOn: Continue").first)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(3, screenshots)
        }
    }

    @Test fun `total flow deadline interrupts a pending request and detaches locally`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { Reply(bytes = backend.session(), delayMs = 2_000) }
            val start = System.nanoTime()
            val (status, _) = run(backend, "- tapOn: Continue", environment = backend.environment + ("REVYL_MAESTRO_FLOW_TIMEOUT_MS" to "150"))
            assertEquals(1, status)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_500)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `community GraalJS context really initializes and evaluates`() {
        GraalJsEngine(platform = "android").use { engine ->
            assertEquals(42, engine.evaluateScript("21 * 2").asInt())
        }
        val runtimePath = requireNotNull(javaClass.classLoader.getResource("com/oracle/truffle/runtime/OptimizedTruffleRuntime.class")).path
        assertTrue(runtimePath.contains("truffle-runtime-24.2.0"))
        assertFalse(runtimePath.contains("truffle-enterprise"))
    }

    @Test fun `installed CLI executes YAML and returns nonzero for assertion and parse failures`() {
        LoopbackBackend("ios").use { backend ->
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            for ((body, expected) in listOf("- assertVisible: Continue" to 0, "- extendedWaitUntil: {visible: '$PRIVATE_SENTINEL', timeout: 100}" to 1, "- invalid$PRIVATE_SENTINEL: [[[" to 1)) {
                val flow = temporary.resolve("cli.yaml")
                Files.writeString(flow, "appId: com.example\n---\n$body\n")
                val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", "ios", flow.toString()).directory(temporary.toFile()).redirectErrorStream(true)
                builder.environment().clear()
                builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temporary.toString(), "JAVA_HOME" to System.getProperty("java.home")) + backend.environment)
                val process = builder.start()
                try {
                    assertTrue(process.waitFor(25, TimeUnit.SECONDS), "CLI did not exit within its deadline")
                    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                    assertEquals(expected, process.exitValue(), output)
                    assertFalse(output.contains(PRIVATE_SENTINEL), output)
                    assertFalse(output.contains(FIXTURE_KEY), output)
                } finally { process.destroyForcibly() }
            }
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `installed CLI runs the maintained smoke example on both platform contracts`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            val example = Path.of("examples/smoke.yaml").toAbsolutePath()
            val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", platform, example.toString())
                .directory(temporary.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temporary.toString(), "JAVA_HOME" to System.getProperty("java.home")) + backend.environment)
            val process = builder.start()
            try {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS), "CLI did not exit within its deadline")
                val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                assertEquals(0, process.exitValue(), output)
                assertTrue(output.contains("Maestro flow passed"))
                assertFalse(output.contains(FIXTURE_KEY))
                assertEquals(1, backend.mutations.size)
                assertTrue(backend.mutations.single().path.endsWith("/tap"))
                assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
                val screenshot = temporary.resolve(".revyl-maestro/smoke.png")
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot)))
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot.parent)))
            } finally { process.destroyForcibly() }
        }
    }
}
