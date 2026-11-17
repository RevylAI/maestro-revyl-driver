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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeHierarchyReadinessTest {
    @TempDir lateinit var temporary: Path

    private fun run(backend: LoopbackBackend, body: String): Pair<Int, String> {
        val flow = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(flow, "appId: com.example\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, flow.toString()), backend.environment, PrintStream(output), PrintStream(output))
        assertFalse(output.toString().contains(PRIVATE_SENTINEL), "CLI disclosed fixture response content")
        return status to output.toString()
    }

    private fun serveHierarchy(backend: LoopbackBackend, firstReads: Int, payload: () -> ByteArray): AtomicInteger {
        val reads = AtomicInteger()
        backend.intercept = { request ->
            if (request.path.endsWith("/hierarchy") && reads.incrementAndGet() <= firstReads) Reply(bytes = payload()) else null
        }
        return reads
    }

    @Test
    fun `iOS empty 0x0 root while the app starts is read again until the app is ready`() {
        LoopbackBackend("ios").use { backend ->
            val reads = serveHierarchy(backend, firstReads = 2) { emptyRoot }
            val (status, output) = run(backend, "- assertVisible: Continue")
            assertEquals(0, status, output)
            assertTrue(reads.get() > 2)
        }
    }

    @Test
    fun `iOS empty 0x0 root that never fills in fails with a bounded timeout naming it`() {
        LoopbackBackend("ios").use { backend ->
            val reads = serveHierarchy(backend, firstReads = Int.MAX_VALUE) { emptyRoot }
            val (status, output) = run(backend, "- assertVisible: Continue")
            assertEquals(1, status, output)
            assertTrue(output.contains("still only an empty 0×0 Application root with no children after waiting 8 seconds"), output)
            assertTrue(reads.get() in 4..12, "read the empty root ${reads.get()} times")
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = [
        "10|0|440|956|0|IDB hierarchy must include a full-screen root frame.",
        "0|0|0|0|1|Invalid IDB viewport.",
    ])
    fun `other invalid iOS hierarchies fail at once with their cause`(x: Int, y: Int, width: Int, height: Int, children: Int, cause: String) {
        LoopbackBackend("ios").use { backend ->
            val child = mapOf("type" to "Button", "AXLabel" to PRIVATE_SENTINEL, "frame" to mapOf("x" to 0, "y" to 0, "width" to 10, "height" to 10))
            val reads = serveHierarchy(backend, firstReads = Int.MAX_VALUE) {
                json.writeValueAsBytes(listOf(mapOf("type" to "Application", "frame" to mapOf("x" to x, "y" to y, "width" to width, "height" to height), "children" to List(children) { child })))
            }
            val (status, output) = run(backend, "- assertVisible: Continue")
            assertEquals(1, status, output)
            assertTrue(output.contains("Revyl returned an invalid native hierarchy: $cause"), output)
            assertEquals(1, reads.get())
        }
    }

    @Test
    fun `unparseable Android hierarchy names only the parser failure type`() {
        LoopbackBackend("android").use { backend ->
            serveHierarchy(backend, firstReads = Int.MAX_VALUE) { "<hierarchy><node $PRIVATE_SENTINEL".toByteArray() }
            val (status, output) = run(backend, "- assertVisible: Continue")
            assertEquals(1, status, output)
            assertTrue(output.contains("Revyl returned an invalid native hierarchy: SAXParseException."), output)
        }
    }

    private companion object {
        val emptyRoot: ByteArray = json.writeValueAsBytes(listOf(mapOf(
            "type" to "Application", "AXLabel" to " ", "frame" to mapOf("x" to 0, "y" to 0, "width" to 0, "height" to 0), "children" to emptyList<Any>(),
        )))
    }
}
