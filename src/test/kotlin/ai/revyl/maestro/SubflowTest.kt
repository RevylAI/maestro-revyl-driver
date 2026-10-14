package ai.revyl.maestro

import maestro.orchestra.yaml.YamlCommandReader
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

class SubflowTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String, name: String = "${UUID.randomUUID()}.yaml", config: String = "appId: com.example"): Path {
        val path = temporary.resolve(name)
        Files.createDirectories(path.parent)
        return Files.writeString(path, "$config\n---\n$body\n")
    }

    private fun inline(body: String): String = "- runFlow:\n    commands:\n" + body.prependIndent("      ")

    private fun run(backend: LoopbackBackend, path: Path): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, path.toString()), backend.environment,
            PrintStream(output), PrintStream(output))
        assertFalse(output.toString().contains(PRIVATE_SENTINEL))
        assertFalse(output.toString().contains(FIXTURE_KEY))
        return status to output.toString()
    }

    private fun reject(path: Path, expectedMessage: String? = null) {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, path)
            assertEquals(1, status, output)
            assertTrue(backend.requests.isEmpty(), output)
            expectedMessage?.let { assertTrue(output.contains(it), output) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `mixed inline and relative file flows execute in native order and allow reuse`(platform: String) {
        flow("- launchApp: {stopApp: false, permissions: {}}", "steps/launch.yaml", "appId: com.child")
        flow(inline("- runFlow: launch.yaml\n- pressKey: Home"), "steps/onboard.yaml")
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, flow("- runFlow: steps/onboard.yaml\n- runFlow: {file: steps/launch.yaml, label: Reuse}\n" + inline("- tapOn: Continue\n- assertVisible: Welcome")))
            assertEquals(0, status, output)
            assertEquals(listOf("launch", "go_home", "launch", "tap"), backend.mutations.map { it.path.substringAfterLast('/') })
            assertEquals("com.child", json.readTree(backend.mutations.first().body).path("bundle_id").textValue())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- runFlow: {when: {}, commands: [{pressKey: Home}]}",
        "- runFlow: {env: {VALUE: null}, commands: [{pressKey: Home}]}",
        "- runFlow: {optional: false, commands: [{pressKey: Home}]}",
        "- runFlow: {optional: true, commands: [{pressKey: Home}]}",
        "- runFlow: {unknown: true, commands: [{pressKey: Home}]}",
        "- runFlow: {label: '\${1}', commands: [{pressKey: Home}]}",
        "- runFlow: {file: missing.yaml, commands: [{pressKey: Home}]}",
        "- runFlow: {file: null}", "- runFlow: {file: 123}", "- runFlow: ''",
        "- runFlow: {file: missing.yaml, file: other.yaml}",
        "- runFlow: '\${PATH}'", "- runFlow: {}", "- runFlow: null",
        "- runFlow: {commands: []}", "- runFlow: {commands: null}", "- runFlow: {commands: {pressKey: Home}}",
        "- inputText: {text: hello, unexpected: true}",
        "- tapOn: {point: '10,20', repeat: 1.5}",
        "- swipe: {direction: UP, duration: 1.5}",
        "- copyTextFrom: {text: Continue, repeat: 2}",
        "- eraseText: 1.5", "- setDarkMode: {value: enabled, optional: true}",
        "- takeScreenshot: ../escape", "- runScript: missing.js", "- evalScript: '\${1}'",
        "- retry: {commands: [{pressKey: Home}]}", "- repeat: {times: 2, commands: [{pressKey: Home}]}",
        "- pressKey: VolumeUp", "- tapOn: {text: Continue, optional: true}",
    ])
    fun `unsupported nested commands and normalized options fail before any request`(body: String) {
        val child = flow(inline(body), "child.yaml")
        reject(flow("- pressKey: Home\n- runFlow: $child"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["env: {VALUE: null}", "onFlowStart: [{runFlow: child.yaml}]", "onFlowComplete: [{pressKey: Home}]", "properties: {}", "unknown: value"])
    fun `nested config options fail before parser expansion and attachment`(config: String) {
        flow("- pressKey: Home", "child.yaml", "appId: com.example\n$config")
        reject(flow("- pressKey: Home\n- runFlow: child.yaml"))
    }

    @Test fun `missing directory oversized and malformed child files fail without requests`() {
        Files.createDirectories(temporary.resolve("directory"))
        Files.writeString(temporary.resolve("large.yaml"), "x".repeat(1024 * 1024 + 1))
        Files.writeString(temporary.resolve("broken.yaml"), "appId: com.example\n---\n- runFlow: [[[")
        for (name in listOf("missing.yaml", "directory", "large.yaml", "broken.yaml")) reject(flow("- pressKey: Home\n- runFlow: $name"))
    }

    @Test fun `direct indirect and symlink cycles are rejected without requests`() {
        reject(flow("- runFlow: direct.yaml", "direct.yaml"), "Cyclic subflow")
        flow("- runFlow: second.yaml", "first.yaml")
        flow("- runFlow: first.yaml", "second.yaml")
        reject(temporary.resolve("first.yaml"), "Cyclic subflow")
        val alias = temporary.resolve("alias.yaml")
        Files.createSymbolicLink(alias, temporary.resolve("aliased.yaml"))
        reject(flow("- runFlow: alias.yaml", "aliased.yaml"), "Cyclic subflow")
    }

    @Test fun `global source expanded depth and aggregate source limits are enforced`() {
        flow(List(500) { "- pressKey: Home" }.joinToString("\n"), "many.yaml")
        reject(flow("- runFlow: many.yaml\n- runFlow: many.yaml"), "999 source-command limit")
        flow(List(10) { "- eraseText: 100" }.joinToString("\n"), "expanded.yaml")
        reject(flow("- runFlow: expanded.yaml"), "1000 expanded-command limit")
        reject(flow((1..17).fold("- pressKey: Home") { body, _ -> inline(body) }), "16-level subflow depth limit")
        for (index in 0..16) flow("- runFlow: depth${index + 1}.yaml", "depth$index.yaml")
        flow("- pressKey: Home", "depth17.yaml")
        reject(temporary.resolve("depth0.yaml"), "16-level subflow depth limit")
        flow("#" + "x".repeat(950_000) + "\n- pressKey: Home", "large-shared.yaml")
        reject(flow(List(9) { "- runFlow: large-shared.yaml" }.joinToString("\n")), "8 MiB total source limit")
    }

    @Test fun `supported depth and expanded count boundaries pass native preflight`() {
        val depth = flow((1..16).fold("- pressKey: Home") { body, _ -> inline(body) })
        FlowSourceOptions.validate(depth)
        FlowPreflight.validate(YamlCommandReader.readCommands(depth), "android")
        val count = flow(inline(List(9) { "- eraseText: 100" }.joinToString("\n") + "\n- eraseText: 99"))
        FlowSourceOptions.validate(count)
        FlowPreflight.validate(YamlCommandReader.readCommands(count), "android")
        val sourceCount = flow(inline(List(998) { "- pressKey: Home" }.joinToString("\n")))
        FlowSourceOptions.validate(sourceCount)
        FlowPreflight.validate(YamlCommandReader.readCommands(sourceCount), "android")
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `clipboard and viewer input span parent child and sibling scopes`(platform: String) {
        flow("- pasteText\n- setClipboard: child", "child.yaml")
        LoopbackBackend(platform).use { backend ->
            val path = flow("- setClipboard: parent\n- runFlow: child.yaml\n" + inline("- pasteText\n- copyTextFrom: {id: name}") + "\n- pasteText\n" + inline("- inputText: direct"))
            val (status, output) = run(backend, path)
            assertEquals(0, status, output)
            assertEquals(listOf("parent", "child", "Existing name", "direct"), backend.viewer.messages.map { it.path("value").textValue() })
            assertEquals(1, backend.viewer.server.requestCount)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `clipboard use before initialization and invalid propagated literals fail before attach`() {
        reject(flow(inline("- pasteText") + "\n- setClipboard: later"))
        reject(flow(inline("- setClipboard: ''") + "\n- pasteText"))
        reject(flow("- setClipboard: ''\n" + inline("- pasteText")))
    }

    @Test fun `nested viewer discovery failure prevents preceding mutation`() {
        LoopbackBackend().use { backend ->
            backend.connectionOverrides = mapOf("status" to "not_ready")
            val (status, output) = run(backend, flow("- pressKey: Home\n" + inline("- inputText: hello")))
            assertEquals(1, status, output)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(2, backend.requests.size)
        }
    }

    @Test fun `nested gestures require capabilities before preceding mutation`() {
        flow(inline("- swipe: {start: '10, 20', end: '30, 40', duration: 321}"), "gesture.yaml")
        for (supported in listOf(false, true)) LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_explicit_drag_duration" to supported)
            val (status, output) = run(backend, flow("- pressKey: Home\n- runFlow: gesture.yaml"))
            assertEquals(if (supported) 0 else 1, status, output)
            if (supported) assertEquals(listOf("go_home", "drag"), backend.mutations.map { it.path.substringAfterLast('/') })
            else assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `nested screenshots are rewritten prepared privately and reject duplicate names`() {
        val name = "nested-${UUID.randomUUID()}"
        val screenshot = Path.of(".revyl-maestro/$name.png")
        flow(inline("- takeScreenshot: $name"), "screenshot.yaml")
        try {
            LoopbackBackend().use { backend ->
                val (status, output) = run(backend, flow("- runFlow: screenshot.yaml"))
                assertEquals(0, status, output)
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot)))
            }
            reject(flow("- pressKey: Home\n- runFlow: screenshot.yaml"))
            Files.delete(screenshot)
            reject(flow("- runFlow: screenshot.yaml\n- runFlow: screenshot.yaml"))
        } finally { Files.deleteIfExists(screenshot) }
    }

    @Test fun `nested transport failure never replays or executes parent continuation`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path.endsWith("/tap")) Reply(503, PRIVATE_SENTINEL.toByteArray()) else null }
            val (status, output) = run(backend, flow(inline("- tapOn: Continue\n- pressKey: Home") + "\n- pressKey: Home"))
            assertEquals(1, status, output)
            assertEquals(listOf("tap"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @Test fun `nested assertion failure never executes later child or parent actions`() {
        flow("- extendedWaitUntil: {visible: Missing, timeout: 100}\n- pressKey: Home", "assertion.yaml")
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- runFlow: assertion.yaml\n- pressKey: Home"))
            assertEquals(1, status, output)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `nested input acknowledgment failure is terminal across scopes`() {
        LoopbackBackend().use { backend ->
            backend.viewer.onInput = { socket, event -> backend.viewer.acknowledge(socket, event, mapOf("success" to false)) }
            val (status, output) = run(backend, flow(inline("- inputText: hello\n- inputText: later") + "\n- pressKey: Home"))
            assertEquals(1, status, output)
            assertEquals(1, backend.viewer.messages.size)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `installed CLI executes file and inline subflows from a different working directory`() {
        flow(inline("- tapOn: Continue\n- assertVisible: Welcome\n- takeScreenshot: nested"), "steps/child.yaml")
        val root = flow("- runFlow: steps/child.yaml")
        LoopbackBackend().use { backend ->
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", "android", root.toString())
                .directory(temporary.toFile()).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to temporary.toString(), "JAVA_HOME" to System.getProperty("java.home")) + backend.environment)
            val process = builder.start()
            try {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS))
                val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                assertEquals(0, process.exitValue(), output)
                assertFalse(output.contains(FIXTURE_KEY))
                assertEquals(1, backend.mutations.size)
                val screenshot = temporary.resolve(".revyl-maestro/nested.png")
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot)))
            } finally { process.destroyForcibly() }
        }
    }
}
