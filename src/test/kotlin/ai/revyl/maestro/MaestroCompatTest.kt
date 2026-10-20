package ai.revyl.maestro

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
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaestroCompatTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String, config: String = "appId: com.example", name: String = "${UUID.randomUUID()}.yaml"): Path {
        val path = temporary.resolve(name)
        Files.createDirectories(path.parent)
        return Files.writeString(path, "$config\n---\n$body\n")
    }

    private fun run(backend: LoopbackBackend, path: Path, environment: Map<String, String> = backend.environment, parameters: Array<String> = emptyArray()): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--maestro-compat", "--session", SESSION, "--platform", backend.platform) + parameters + path.toString(),
            environment, PrintStream(output), PrintStream(output))
        val text = output.toString()
        assertFalse(text.contains(FIXTURE_KEY))
        assertFalse(text.contains(PRIVATE_SENTINEL))
        return status to text
    }

    private fun validate(path: Path, platform: String = "android", parameters: Array<String> = emptyArray()): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--maestro-compat", "--validate-only", "--platform", platform) + parameters + path.toString(),
            emptyMap(), PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    @Test fun `optional assertion failure and optional native selectors preserve continuation`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - assertTrue:
                    condition: ${'$'}{false}
                    optional: true
                - assertVisible: {text: Continue, optional: true}
                - tapOn: {id: continue, optional: true}
                - assertVisible: Welcome
                - pressKey: Enter
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("tap", "key"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @Test fun `repeat times and native while conditions execute bounded iterations`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - repeat:
                    times: 2
                    commands: [{pressKey: Enter}]
                - evalScript: ${'$'}{output.counter = 0}
                - repeat:
                    times: 5
                    while:
                      true: ${'$'}{output.counter < 2}
                    commands:
                      - evalScript: ${'$'}{output.counter++}
                      - pressKey: Backspace
                - assertTrue: ${'$'}{output.counter === 2}
                - repeat:
                    while: {visible: Continue}
                    commands: [{tapOn: Continue}]
                - assertVisible: Welcome
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("key", "key", "key", "key", "tap"), backend.mutations.map { it.path.substringAfterLast('/') })
            assertEquals(listOf("ENTER", "ENTER", "BACKSPACE", "BACKSPACE"), backend.mutations.take(4).map { json.readTree(it.body).path("key").textValue() })
        }
    }

    @Test fun `retry reexecutes a Maestro assertion failure and stops after success`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - evalScript: ${'$'}{output.attempts = 0}
                - retry:
                    maxRetries: 2
                    commands:
                      - evalScript: ${'$'}{output.attempts++}
                      - assertTrue: ${'$'}{output.attempts === 2}
                      - pressKey: Enter
                - assertTrue: ${'$'}{output.attempts === 2}
                - pressKey: Home
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("key", "go_home"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `evalScript output and maestro expressions use the real native engine`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, flow("""
                - evalScript: ${'$'}{output.target = 'Con' + 'tinue'}
                - assertVisible: ${'$'}{output.target}
                - copyTextFrom: {id: name}
                - assertTrue: ${'$'}{maestro.copiedText === 'Existing name'}
                - evalScript: ${'$'}{output.message = maestro.copiedText.toUpperCase() + '-' + (2 + 3)}
                - inputText: ${'$'}{output.message}
                - tapOn: ${'$'}{output.target}
                - assertVisible: ${'$'}{'Wel' + 'come'}
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("EXISTING NAME-5"), backend.viewer.messages.map { it.path("value").textValue() })
            assertEquals(listOf("tap"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @Test fun `runScript resolves relative to its containing nested flow with scoped env`() {
        val script = temporary.resolve("steps/scripts/message.js")
        Files.createDirectories(script.parent)
        Files.writeString(script, "output.message = PREFIX.toUpperCase() + '-' + (6 * 7);")
        flow("""
            - runScript:
                file: scripts/message.js
                env: {PREFIX: child}
            - assertTrue: ${'$'}{output.message === 'CHILD-42'}
            - assertTrue: ${'$'}{PREFIX === 'parent'}
            - inputText: ${'$'}{output.message}
            - runScript: scripts/message.js
            - assertTrue: ${'$'}{output.message === 'PARENT-42'}
        """.trimIndent(), name = "steps/child.yaml")
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- runFlow: steps/child.yaml\n- assertTrue: ${'$'}{output.message === 'PARENT-42'}", "appId: com.example\nenv: {PREFIX: parent}"))
            assertEquals(0, status, output)
            assertEquals(listOf("CHILD-42"), backend.viewer.messages.map { it.path("value").textValue() })
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `when true hooks and nested env preserve Maestro evaluation order and scope`() {
        flow("- inputText: ${'$'}{VALUE}\n- inputText: ${'$'}{PASSED}", "appId: com.example\nenv: {VALUE: file}", "child.yaml")
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - runFlow:
                    when:
                      true: ${'$'}{VALUE === 'root' && output.started === 'root'}
                    env: {VALUE: caller, PASSED: '${'$'}{VALUE + "-passed"}'}
                    file: child.yaml
                - runFlow:
                    when: {true: '${'$'}{false}'}
                    commands: [{pressKey: Home}]
                - inputText: ${'$'}{VALUE}
            """.trimIndent(), """
                appId: com.example
                env: {VALUE: root}
                onFlowStart:
                  - evalScript: ${'$'}{output.started = VALUE}
                  - inputText: ${'$'}{VALUE + '-start'}
                onFlowComplete:
                  - assertTrue: ${'$'}{VALUE === 'root' && output.started === 'root'}
                  - inputText: ${'$'}{VALUE + '-complete'}
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("root-start", "file", "root-passed", "root", "root-complete"), backend.viewer.messages.map { it.path("value").textValue() })
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `CLI and inherited Maestro parameters are available without inheriting arbitrary environment`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - assertTrue: ${'$'}{MAESTRO_SHARED === 'cli'}
                - assertTrue: ${'$'}{MAESTRO_INHERITED === 'inherited'}
                - assertTrue: ${'$'}{MAESTRO_FILENAME === 'parameters'}
                - assertTrue: ${'$'}{MAESTRO_SHARD_ID === '1' && MAESTRO_SHARD_INDEX === '0'}
                - assertTrue: ${'$'}{typeof UNRELATED_PARAMETER === 'undefined'}
                - inputText: ${'$'}{USER_VALUE + '-' + MAESTRO_INHERITED}
            """.trimIndent(), name = "parameters.yaml"),
                backend.environment + mapOf("MAESTRO_SHARED" to "environment", "MAESTRO_INHERITED" to "inherited", "MAESTRO_SHARD_ID" to "99", "UNRELATED_PARAMETER" to "unavailable"),
                arrayOf("-e", "MAESTRO_SHARED=cli", "--env", "USER_VALUE=supplied"))
            assertEquals(0, status, output)
            assertEquals(listOf("supplied-inherited"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @Test fun `JavaScript http helper reaches loopback without forwarding backend authentication`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path == "/script-data") Reply(bytes = "{\"message\":\"loopback-result\"}".toByteArray()) else null }
            val (status, output) = run(backend, flow("""
                - evalScript: ${'$'}{output.response = http.get('${backend.origin}/script-data')}
                - assertTrue: ${'$'}{output.response.ok && output.response.status === 200}
                - inputText: ${'$'}{JSON.parse(output.response.body).message}
            """.trimIndent()))
            assertEquals(0, status, output)
            val scriptRequest = backend.requests.single { it.path == "/script-data" }
            assertEquals("GET", scriptRequest.method)
            assertEquals(null, scriptRequest.authorization)
            assertEquals(null, scriptRequest.agent)
            assertTrue(backend.requests.filterNot { it === scriptRequest }.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
            assertEquals(listOf("loopback-result"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["repeat", "retry", "onFlowStart", "onFlowComplete"])
    fun `capability checks inspect loops and hooks before earlier mutations`(scope: String) {
        val nested = "- repeat: {times: 1, commands: [{retry: {maxRetries: 1, commands: [{hideKeyboard: {}}]}}]}"
        val config = if (scope.startsWith("onFlow")) "appId: com.example\n$scope:\n  - pressKey: Enter\n" + nested.prependIndent("  ") else "appId: com.example\nonFlowStart: [{pressKey: Enter}]"
        val body = when (scope) {
            "repeat" -> "- repeat: {while: {true: '${'$'}{false}'}, commands: [{hideKeyboard: {}}]}"
            "retry" -> "- retry: {maxRetries: 1, commands: [{repeat: {times: 1, commands: [{hideKeyboard: {}}]}}]}"
            else -> "- assertTrue: ${'$'}{true}"
        }
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_keyboard_control" to false)
            val (status, output) = run(backend, flow("- pressKey: Home\n$body", config))
            assertEquals(1, status, output)
            assertTrue(backend.requests.any { it.path.endsWith("/health") }, output)
            assertTrue(backend.mutations.isEmpty(), output)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["malformed", "negative", "wrong-action", "string-success", "http-error", "disconnect"])
    fun `uncertain proxy failure cannot be swallowed by optional retry or completion hooks`(response: String) {
        LoopbackBackend().use { backend ->
            backend.intercept = { request ->
                if (!request.path.endsWith("/tap")) null else when (response) {
                    "malformed" -> Reply(bytes = "not-json-$PRIVATE_SENTINEL".toByteArray())
                    "negative" -> Reply(bytes = "{\"success\":false,\"action\":\"tap\"}".toByteArray())
                    "wrong-action" -> Reply(bytes = "{\"success\":true,\"action\":\"key\"}".toByteArray())
                    "string-success" -> Reply(bytes = "{\"success\":\"true\",\"action\":\"tap\"}".toByteArray())
                    "http-error" -> Reply(503, PRIVATE_SENTINEL.toByteArray())
                    else -> Reply(bytes = byteArrayOf(), disconnect = true)
                }
            }
            val (status, output) = run(backend, flow("""
                - retry:
                    maxRetries: 2
                    commands:
                      - tapOn: {point: '20, 30', optional: true}
                      - pressKey: Enter
                - pressKey: Home
            """.trimIndent(), "appId: com.example\nonFlowComplete: [{pressKey: Backspace}]"))
            assertEquals(1, status, output)
            assertEquals(listOf("tap"), backend.mutations.map { it.path.substringAfterLast('/') }, output)
            assertEquals(json.readTree("{\"x\":20,\"y\":30}"), json.readTree(backend.mutations.single().body))
        }
    }

    @Test fun `process environment API key is unavailable to JavaScript and viewer input`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - assertTrue: ${'$'}{typeof REVYL_API_KEY === 'undefined'}
                - assertTrue: ${'$'}{typeof REVYL_MAESTRO_API_URL === 'undefined'}
                - inputText: ${'$'}{typeof REVYL_API_KEY}
            """.trimIndent()), backend.environment + ("UNRELATED_SECRET" to PRIVATE_SENTINEL))
            assertEquals(0, status, output)
            assertEquals(listOf("undefined"), backend.viewer.messages.map { it.path("value").textValue() })
            assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" })
        }
    }

    @Test fun `YAML env cannot expose the reserved API key name to JavaScript`() {
        Files.writeString(temporary.resolve("credential-check.js"), "output.safeScriptEnv = typeof REVYL_API_KEY === 'undefined' && typeof SCRIPT_ALIAS === 'undefined';")
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - assertTrue: ${'$'}{typeof REVYL_API_KEY === 'undefined'}
                - assertTrue: ${'$'}{typeof YAML_ALIAS === 'undefined'}
                - runFlow:
                    env: {REVYL_API_KEY: nested-fake-key}
                    commands:
                      - assertTrue: ${'$'}{typeof REVYL_API_KEY === 'undefined'}
                - runScript:
                    file: credential-check.js
                    env: {REVYL_API_KEY: script-fake-key, SCRIPT_ALIAS: '$FIXTURE_KEY'}
                - assertTrue: ${'$'}{output.safeScriptEnv}
                - inputText: ${'$'}{typeof REVYL_API_KEY}
            """.trimIndent(), "appId: com.example\nenv: {REVYL_API_KEY: yaml-fake-key, YAML_ALIAS: '$FIXTURE_KEY'}"))
            assertEquals(0, status, output)
            assertEquals(listOf("undefined"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["REVYL_API_KEY=not-a-real-key", "ALIAS=offline-fixture-key"])
    fun `CLI parameters cannot import the API key or its reserved name`(parameter: String) {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- pressKey: Home"), parameters = arrayOf("-e", parameter))
            assertEquals(1, status, output)
            assertTrue(backend.requests.isEmpty(), output)
        }
    }

    @Test fun `inherited Maestro aliases cannot import the API key`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- pressKey: Home"), backend.environment + ("MAESTRO_ALIAS" to FIXTURE_KEY))
            assertEquals(1, status, output)
            assertTrue(backend.requests.isEmpty(), output)
        }
    }

    @Test fun `validate only accepts compatibility and parameter flags in either order without attachment`() {
        LoopbackBackend().use { backend ->
            val path = flow("- evalScript: ${'$'}{output.value = VALUE}\n- assertTrue: ${'$'}{output.value === 'ok'}")
            val orders = listOf(
                arrayOf("--maestro-compat", "-e", "VALUE=ok", "--validate-only", "--platform", "android", path.toString()),
                arrayOf("--validate-only", "--platform", "android", path.toString(), "--env", "VALUE=ok", "--maestro-compat"),
            )
            orders.forEach { args ->
                val output = ByteArrayOutputStream()
                assertEquals(0, runCli(args, backend.environment, PrintStream(output), PrintStream(output)), output.toString())
                assertTrue(output.toString().contains("Maestro compatibility mode"))
                assertFalse(output.toString().contains(FIXTURE_KEY))
            }
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test fun `dynamic screenshot names support crop and same-flow screenshot assertions`() {
        val name = "compat-${UUID.randomUUID()}"
        val artifact = Path.of(".revyl-maestro", "$name.png")
        try {
            LoopbackBackend().use { backend ->
                val (status, output) = run(backend, flow("""
                    - evalScript: ${'$'}{output.name = '$name'}
                    - takeScreenshot: {path: '${'$'}{output.name}', cropOn: {id: continue}}
                    - assertScreenshot: {path: '${'$'}{output.name}', cropOn: {id: continue}, thresholdPercentage: 100}
                    - pressKey: Enter
                """.trimIndent()))
                assertEquals(0, status, output)
                assertTrue(Files.size(artifact) > 32)
                val image = ImageIO.read(artifact.toFile())
                assertEquals(80, image.width)
                assertEquals(40, image.height)
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(artifact))
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(artifact.parent))
                assertEquals(listOf("key"), backend.mutations.map { it.path.substringAfterLast('/') })
            }
        } finally { Files.deleteIfExists(artifact) }
    }

    @Test fun `dynamic artifact traversal fails after earlier compat actions without escaping output`() {
        val outside = Path.of("escape-${UUID.randomUUID()}.png").toAbsolutePath()
        try {
            LoopbackBackend().use { backend ->
                val (status, output) = run(backend, flow("""
                    - pressKey: Enter
                    - evalScript: ${'$'}{output.name = '../${outside.fileName.toString().removeSuffix(".png")}'}
                    - takeScreenshot: ${'$'}{output.name}
                """.trimIndent()))
                assertEquals(1, status, output)
                assertEquals(listOf("key"), backend.mutations.map { it.path.substringAfterLast('/') })
                assertFalse(Files.exists(outside))
                assertFalse(Files.exists(Path.of(".revyl-maestro", outside.fileName.toString())))
            }
        } finally { Files.deleteIfExists(outside) }
    }

    @Test fun `compat validate-only accepts loops whose expanded count exceeds strict-mode limit`() {
        val path = flow("""
            - repeat:
                times: 1001
                commands: [{pressKey: Enter}]
        """.trimIndent())
        val (status, output) = validate(path)
        assertEquals(0, status, output)
        assertFalse(output.contains(FIXTURE_KEY))
    }

    @Test fun `compat validate-only accepts extended waits above strict-mode timeout limit`() {
        val path = flow("- extendedWaitUntil: {visible: Continue, timeout: 120001}")
        val (status, output) = validate(path)
        assertEquals(0, status, output)
        assertFalse(output.contains(FIXTURE_KEY))
    }

    @ParameterizedTest
    @ValueSource(strings = ["javascript", "repeat"])
    fun `compat total flow timeout stops infinite JS or repeat work without leaked requests`(kind: String) {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path == "/script-ping") Reply(bytes = "ok".toByteArray()) else null }
            val body = if (kind == "javascript") {
                "- evalScript: ${'$'}{(() => { while (true) { http.get('${backend.origin}/script-ping'); } })()}"
            } else {
                """
                    - repeat:
                        while: {true: '${'$'}{true}'}
                        commands: [{pressKey: Enter}]
                """.trimIndent()
            }
            val start = System.nanoTime()
            val (status, output) = run(backend, flow(body), backend.environment + ("REVYL_MAESTRO_FLOW_TIMEOUT_MS" to "300"))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(1, status, output)
            assertTrue(output.contains("timed out"), output)
            assertTrue(elapsed < 2_000, "timeout took ${elapsed}ms")
            assertTrue(backend.requests.any { if (kind == "javascript") it.path == "/script-ping" else it.path.endsWith("/key") }, "$kind loop did not begin before the deadline")
            val requests = backend.requests.size
            Thread.sleep(250)
            assertEquals(requests, backend.requests.size, "${kind} work continued after runCli returned")
        }
    }

    @Test fun `compat scrollUntilVisible does not scroll an already-visible element`() {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_explicit_drag_duration" to true)
            val (status, output) = run(backend, flow("""
                - scrollUntilVisible:
                    element: Continue
                    timeout: 100
                - assertVisible: Continue
            """.trimIndent()))
            assertEquals(0, status, output)
            assertTrue(backend.mutations.isEmpty(), output)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `compat element-relative tap and swipe use native element bounds`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            backend.healthOverrides = mapOf("supports_explicit_drag_duration" to true)
            val (status, output) = run(backend, flow("""
                - tapOn: {id: continue, point: '25%, 75%'}
                - swipe:
                    from: {id: continue, point: '25%, 75%'}
                    direction: LEFT
                    duration: 321
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("tap", "drag"), backend.mutations.map { it.path.substringAfterLast('/') })
            assertEquals(json.readTree("{\"x\":30,\"y\":110}"), json.readTree(backend.mutations[0].body))
            assertEquals(json.readTree("{\"start_x\":30,\"start_y\":110,\"end_x\":10,\"end_y\":110,\"duration_ms\":321}"), json.readTree(backend.mutations[1].body))
        }
    }
}
