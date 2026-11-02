package ai.revyl.maestro

import maestro.orchestra.InputTextCommand
import maestro.orchestra.MaestroCommand
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureArtifactsTest {
    @TempDir lateinit var temporary: Path

    private val missingInSubflow = """
        - assertVisible: Continue
        - runFlow:
            commands:
              - assertVisible: Existing name
              - extendedWaitUntil:
                  visible:
                    id: login_button
                  timeout: 100
        - tapOn: Continue
    """.trimIndent()

    private fun run(backend: LoopbackBackend, body: String, vararg options: String): Pair<Int, String> {
        val flow = Files.writeString(temporary.resolve("${UUID.randomUUID()}.yaml"), "appId: com.example\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf(*options, "--session", SESSION, "--platform", backend.platform, flow.toString()), backend.environment, PrintStream(output), PrintStream(output))
        assertFalse(output.toString().contains(FIXTURE_KEY))
        assertFalse(output.toString().contains(PRIVATE_SENTINEL))
        return status to output.toString()
    }

    private fun artifactDirectory(output: String): Path =
        Path.of(Regex("Failure artifacts \\(private\\): (\\S+) ").find(output)!!.groupValues[1])

    @Test fun `failures stay suppressed without the flag`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, missingInSubflow)
            assertEquals(1, status, output)
            assertTrue(output.contains("rerun with --failure-artifacts"), output)
            assertFalse(output.contains("login_button"), output)
            assertFalse(output.contains("Failure artifacts"), output)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `flag reports the nested failed step and saves private hierarchy and screenshot`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, missingInSubflow, "--failure-artifacts")
            val directory = artifactDirectory(output)
            try {
                assertEquals(1, status, output)
                assertTrue(Regex("Failed command: Run flow.* > Assert that id: login_button is visible\n").containsMatchIn(output), output)
                assertTrue(output.contains("Reason: "), output)
                assertFalse(output.contains("Customer details are suppressed"), output)
                assertTrue(backend.mutations.isEmpty())
                val hierarchy = directory.resolve("hierarchy.json")
                val screenshot = directory.resolve("screenshot.png")
                assertTrue(Files.readString(hierarchy).contains("Existing name"))
                assertTrue(Files.readAllBytes(screenshot).copyOfRange(1, 4).contentEquals("PNG".toByteArray()))
                listOf(hierarchy, screenshot).forEach { assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it))) }
                listOf(directory, directory.parent).forEach { assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it))) }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test fun `passing flows write no failure artifacts`() {
        LoopbackBackend().use { backend ->
            val (status, output) = run(backend, "- assertVisible: Continue", "--failure-artifacts")
            assertEquals(0, status, output)
            assertFalse(output.contains("Failure artifacts"), output)
        }
    }

    @Test fun `flag is rejected with validate-only`() {
        val flow = Files.writeString(temporary.resolve("flow.yaml"), "appId: com.example\n---\n- assertVisible: Continue\n")
        val output = ByteArrayOutputStream()
        assertEquals(1, runCli(arrayOf("--failure-artifacts", "--validate-only", "--platform", "android", flow.toString()), emptyMap(), PrintStream(output), PrintStream(output)))
        assertTrue(output.toString().startsWith("Usage:"), output.toString())
    }

    @Test fun `typed text is omitted from the failed command`() {
        val description = failureDescription(MaestroCommand(inputTextCommand = InputTextCommand("hunter2")))
        assertFalse(description.contains("hunter2"), description)
    }
}
