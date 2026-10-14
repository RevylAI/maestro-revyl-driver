package ai.revyl.maestro

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionalFlowTest {
    @TempDir lateinit var temporary: Path

    private fun flow(body: String, config: String = "appId: com.example", name: String = "${UUID.randomUUID()}.yaml"): Path =
        Files.writeString(temporary.resolve(name), "$config\n---\n$body\n")

    private fun run(backend: LoopbackBackend, flow: Path): Pair<Int, String> {
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow.toString()), backend.environment, PrintStream(output), PrintStream(output))
        assertFalse(output.toString().contains(FIXTURE_KEY))
        assertFalse(output.toString().contains(PRIVATE_SENTINEL))
        return status to output.toString()
    }

    private fun reject(flow: Path, expectedMessage: String? = null) {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow)
            assertEquals(1, status, output)
            assertTrue(backend.requests.isEmpty(), output)
            expectedMessage?.let { assertTrue(output.contains(it), output) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `native visible notVisible and platform conditions compose and skip without failure`(platform: String) {
        val other = if (platform == "android") "ios" else "android"
        flow("- pressKey: Home", name = "child.yaml")
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, flow("""
                - runFlow:
                    when: {visible: Continue, notVisible: Missing, platform: $platform}
                    file: child.yaml
                - runFlow:
                    when: {notVisible: Missing, platform: $platform}
                    commands: [{pressKey: Home}]
                - runFlow:
                    when: {platform: $other}
                    commands: [{pressKey: Home}]
                - pressKey: Enter
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("go_home", "go_home", "key"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["visible: Missing", "notVisible: Continue"])
    fun `false visibility conditions skip child mutations and continue parent`(condition: String) {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- runFlow: {when: {$condition}, commands: [{pressKey: Home}]}\n- pressKey: Enter"))
            assertEquals(0, status, output)
            assertEquals(listOf("key"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @Test fun `a wholly skipped flow succeeds without mutations`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("- runFlow: {when: {platform: ios}, commands: [{pressKey: Home}]}"))
            assertEquals(0, status, output)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `env follows native file precedence inline inheritance nested interpolation and restore`() {
        flow("- inputText: '\${VALUE}'\n- inputText: '\${PASSED}'", "appId: com.example\nenv: {VALUE: child}", "child.yaml")
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - inputText: '${'$'}{VALUE}'
                - runFlow:
                    env: {VALUE: caller, PASSED: '${'$'}{VALUE}-parameter'}
                    file: child.yaml
                - runFlow:
                    env: {VALUE: '${'$'}{VALUE}-inline'}
                    commands:
                      - runFlow:
                          env: {DEEP: '${'$'}{VALUE}-deep'}
                          commands: [{inputText: '${'$'}{DEEP}'}]
                - inputText: '${'$'}{VALUE}'
                - runFlow: {env: {}, commands: [{inputText: '${'$'}{VALUE}'}]}
            """.trimIndent(), "appId: com.example\nenv: {VALUE: parent}"))
            assertEquals(0, status, output)
            assertEquals(listOf("parent", "child", "parent-parameter", "parent-inline-deep", "parent", "parent"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @Test fun `native inline repeated definitions are evaluated in published order`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - runFlow:
                    env: {VALUE: '${'$'}{VALUE}-child'}
                    commands:
                      - inputText: '${'$'}{VALUE}'
                      - inputText: '${'$'}{VALUE}'
                - inputText: '${'$'}{VALUE}'
            """.trimIndent(), "appId: com.example\nenv: {VALUE: parent}"))
            assertEquals(0, status, output)
            assertEquals(listOf("parent-child-child", "parent-child-child", "parent"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @Test fun `conditions interpolate the enclosing scope before subflow env is applied`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - runFlow:
                    when: {visible: '${'$'}{TARGET}'}
                    env: {TARGET: Missing}
                    commands: [{inputText: '${'$'}{TARGET}'}]
            """.trimIndent(), "appId: com.example\nenv: {TARGET: Continue}"))
            assertEquals(0, status, output)
            assertEquals("Missing", backend.viewer.messages.single().path("value").textValue())
        }
    }

    @Test fun `resolved native selectors points app IDs screenshots and text validate before execution`() {
        val name = "parameter-${UUID.randomUUID()}"
        val screenshot = Path.of(".revyl-maestro/$name.png")
        try {
            LoopbackBackend().use { backend ->
                val (status, output) = run(backend, flow("""
                    - launchApp: {appId: '${'$'}{APP}', stopApp: false, permissions: {}}
                    - tapOn: {point: '${'$'}{POINT}'}
                    - assertVisible: '${'$'}{TARGET}'
                    - takeScreenshot: '${'$'}{SHOT}'
                """.trimIndent(), "appId: com.example\nenv: {APP: com.child, POINT: '50%, 50%', TARGET: Welcome, SHOT: $name}"))
                assertEquals(0, status, output)
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("com.child", json.readTree(backend.mutations.first().body).path("bundle_id").textValue())
            }
        } finally { Files.deleteIfExists(screenshot) }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "- takeScreenshot: '${'$'}{VALUE}'", "- tapOn: {point: '${'$'}{VALUE}'}",
        "- launchApp: {appId: '${'$'}{VALUE}', stopApp: false, permissions: {}}",
        "- extendedWaitUntil: {visible: Continue, timeout: '${'$'}{VALUE}'}",
        "- setLocation: {latitude: '${'$'}{VALUE}', longitude: '0'}",
    ])
    fun `invalid resolved values in skipped branches reject earlier mutations`(command: String) {
        reject(flow("- pressKey: Home\n- runFlow:\n    when: {platform: ios}\n    commands:\n" + command.prependIndent("      "), "appId: com.example\nenv: {VALUE: '../invalid'}"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{true: 'true'}", "{true: '${'$'}{FLAG}'}", "{}", "null", "{platform: web}",
        "{platform: '${'$'}{PLATFORM}'}", "{optional: true, visible: Continue}", "{visible: null}",
        "{visible: {text: Continue, optional: true}}", "{visible: Continue, unknown: true}", "{visible: {}}",
    ])
    fun `unsupported conditions fail before requests`(condition: String) {
        reject(flow("- pressKey: Home\n- runFlow: {when: $condition, commands: [{pressKey: Home}]}"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{VALUE: null}", "{VALUE: 42}", "{VALUE: true}", "{VALUE: []}", "{VALUE: {}}", "[]", "null",
        "{lowercase: value}", "{JSON: value}", "{VALUE: '${'$'}{MISSING}'}",
        "{VALUE: '${'$'}{http.get(\"https://example.com\")}'}", "{VALUE: '${'$'}{Java.type(\"java.lang.System\").getenv()}'}",
        "{VALUE: '${'$'}{globalThis}'}", "{VALUE: '${'$'}{maestro.copiedText}'}", "{VALUE: '${'$'}{1+1}'}",
        "{VALUE: '${'$'}{VALUE}'}", "{VALUE: '\\${'$'}{VALUE}'}",
    ])
    fun `invalid env maps undefined references and arbitrary scripts fail before requests`(env: String) {
        reject(flow("- pressKey: Home\n- runFlow: {when: {platform: ios}, env: $env, commands: [{inputText: '${'$'}{VALUE}'}]}"))
        reject(flow("- pressKey: Home", "appId: com.example\nenv: $env"))
    }

    @Test fun `oversized resolved strings and env composition remain bounded`() {
        val large = "x".repeat(16_384)
        reject(flow("- pressKey: Home\n- inputText: '\${VALUE}\${VALUE}'", "appId: com.example\nenv: {VALUE: '$large'}"))
        reject(flow("- pressKey: Home", "appId: com.example\nenv: {VALUE: '${large}x'}"))
        val tooMany = (0..64).joinToString(", ") { "KEY$it: value" }
        reject(flow("- pressKey: Home", "appId: com.example\nenv: {$tooMany}"))
        val commands = List(65) { "- pressKey: Home" }.joinToString("\n").prependIndent("      ")
        reject(flow("- runFlow:\n    env: {VALUE: '$large'}\n    commands:\n$commands"))
        reject(flow("- inputText: '" + "\${VALUE}".repeat(513) + "'", "appId: com.example\nenv: {VALUE: '$large'}"), "8 MiB interpolation limit")
        reject(flow("- inputText: '" + "\${VALUE}".repeat(16_385) + "'", "appId: com.example\nenv: {VALUE: ''}"), "16384 parameter-reference limit")
        val definitions = (0..63).joinToString(", ") { "KEY$it: small" }
        reject(flow("- runFlow:\n    env: {$definitions}\n    commands:\n$commands"), "4096 parameter-definition limit")
    }

    @Test fun `env sibling definitions do not see each other before native batch assignment`() {
        reject(flow("- inputText: '\${SECOND}'", "appId: com.example\nenv: {FIRST: one, SECOND: '\${FIRST}'}"))
    }

    @Test fun `subflow variables do not escape into siblings or parent`() {
        reject(flow("- runFlow: {env: {CHILD: local}, commands: [{pressKey: Home}]}\n- inputText: '\${CHILD}'"))
    }

    @Test fun `conditional clipboard initialization cannot justify later paste`() {
        reject(flow("- runFlow: {when: {platform: ios}, commands: [{setClipboard: safe}]}\n- pasteText"))
        reject(flow("- setClipboard: safe\n- runFlow: {when: {platform: ios}, commands: [{setClipboard: ''}]}\n- pasteText"))
        reject(flow("- setClipboard: ''\n- runFlow: {when: {visible: Continue}, commands: [{setClipboard: safe}]}\n- pasteText"))
        reject(flow("- runFlow: {when: {platform: ios}, commands: [pasteText]}"))
    }

    @Test fun `clipboard initialized inside conditional branch supports local paste and safe merged state`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, flow("""
                - setClipboard: parent
                - runFlow:
                    when: {visible: Continue}
                    commands: [{setClipboard: child}, pasteText]
                - runFlow:
                    when: {platform: ios}
                    commands: [{setClipboard: skipped}, pasteText]
                - pasteText
            """.trimIndent()))
            assertEquals(0, status, output)
            assertEquals(listOf("child", "child"), backend.viewer.messages.map { it.path("value").textValue() })
        }
    }

    @Test fun `condition hierarchy failure is terminal rather than a skip`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path.endsWith("/hierarchy")) Reply(503, PRIVATE_SENTINEL.toByteArray()) else null }
            val (status, output) = run(backend, flow("- runFlow: {when: {visible: Continue}, commands: [{pressKey: Home}]}\n- pressKey: Home"))
            assertEquals(1, status, output)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(1, backend.requests.count { it.path.endsWith("/hierarchy") })
        }
    }

    @Test fun `skipped branches still require viewer authorization and gesture capability`() {
        for (command in listOf("inputText: hello", "swipe: {start: '10,20', end: '30,40'}")) {
            LoopbackBackend().use { backend ->
                backend.connectionOverrides = mapOf("status" to "not_ready")
                val (status, output) = run(backend, flow("- pressKey: Home\n- runFlow: {when: {platform: ios}, commands: [{$command}]}"))
                assertEquals(1, status, output)
                assertTrue(backend.mutations.isEmpty())
            }
        }
    }

    @Test fun `skipped branches still reject unsupported nested commands and resolved screenshot collisions`() {
        reject(flow("- pressKey: Home\n- runFlow: {when: {platform: ios}, commands: [{runScript: missing.js}]}"))
        val name = "conditional-${UUID.randomUUID()}"
        val screenshot = Path.of(".revyl-maestro/$name.png")
        try {
            reject(flow("- takeScreenshot: '\${SHOT}'\n- runFlow: {when: {platform: ios}, commands: [{takeScreenshot: '\${SHOT}'}]}", "appId: com.example\nenv: {SHOT: $name}"))
        } finally { Files.deleteIfExists(screenshot) }
    }

    @Test fun `parameter concatenation cannot introduce a second executable expression`() {
        reject(flow("- inputText: '\${PREFIX}\${SUFFIX}'", "appId: com.example\nenv: {PREFIX: '$', SUFFIX: '{http.get(\"https://example.com\")}'}"))
    }

    @Test fun `installed CLI cannot resolve process credentials or shell parameters`() {
        LoopbackBackend().use { backend ->
            val binary = Path.of("build/install/revyl-maestro/bin/revyl-maestro").toAbsolutePath()
            for (name in listOf("REVYL_API_KEY", "SHELL_ONLY", "MAESTRO_SECRET")) {
                val file = flow("- pressKey: Home\n- inputText: '\${$name}'")
                val builder = ProcessBuilder(binary.toString(), "--session", SESSION, "--platform", "android", file.toString()).redirectErrorStream(true)
                builder.environment().clear()
                builder.environment().putAll(mapOf("HOME" to temporary.toString(), "PATH" to "/usr/bin:/bin", "JAVA_HOME" to System.getProperty("java.home"), "SHELL_ONLY" to PRIVATE_SENTINEL, "MAESTRO_SECRET" to PRIVATE_SENTINEL) + backend.environment)
                val process = builder.start()
                try {
                    assertTrue(process.waitFor(25, TimeUnit.SECONDS))
                    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                    assertEquals(1, process.exitValue(), output)
                    assertFalse(output.contains(FIXTURE_KEY))
                    assertFalse(output.contains(PRIVATE_SENTINEL))
                    assertTrue(backend.requests.isEmpty())
                } finally { process.destroyForcibly() }
            }
        }
    }
}
