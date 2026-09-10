package ai.revyl.maestro

import maestro.MaestroException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundaryTest {
    private fun client(backend: LoopbackBackend, environment: Map<String, String> = backend.environment): RevylClient =
        RevylClient(ConnectionSettings.fromEnvironment(environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10))

    @ParameterizedTest
    @ValueSource(strings = ["http://example.com", "https://user:secret@example.com", "https://example.com/path", "https://example.com?token=secret", "https://example.com#fragment", "file:///etc/passwd", "https://example.com:99999", "https://", "http://127.0.0.1.example.com", "http://2130706433", "https://example.com/%2e%2e", "https://example.com:0"])
    fun `endpoints fail closed without leaking supplied values`(origin: String) {
        val failure = assertFailsWith<AdapterFailure> { ConnectionSettings.fromEnvironment(mapOf("REVYL_API_KEY" to FIXTURE_KEY, "REVYL_MAESTRO_API_URL" to origin)) }
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertFalse(failure.message.orEmpty().contains(origin))
    }

    @Test fun `configuration accepts only credentials from environment and bounded integer timeouts`() {
        assertFailsWith<AdapterFailure> { ConnectionSettings.fromEnvironment(emptyMap()) }
        for (key in listOf("", " ", "contains\nnewline", "contains\rheader")) {
            assertFailsWith<AdapterFailure> { ConnectionSettings.fromEnvironment(mapOf("REVYL_API_KEY" to key)) }
        }
        for (timeout in listOf("0", "-1", "120001", "1.2", "bad", "")) {
            assertFailsWith<AdapterFailure> { ConnectionSettings.fromEnvironment(mapOf("REVYL_API_KEY" to FIXTURE_KEY, "REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to timeout)) }
        }
        val settings = ConnectionSettings.fromEnvironment(mapOf("REVYL_API_KEY" to FIXTURE_KEY))
        assertEquals(30_000, settings.timeoutMs)
        assertEquals("https://backend.revyl.ai", settings.origin)
        assertFalse(settings.toString().contains(FIXTURE_KEY))
    }

    @Test fun `UUID validation precedes every request`() {
        LoopbackBackend().use { backend ->
            for (session in listOf("not-a-uuid", "$SESSION/../other", SESSION.replace("-", ""), "1-1-1-1-1")) {
                client(backend).use { assertFailsWith<AdapterFailure> { it.attach(session, "android") } }
            }
            assertTrue(backend.requests.isEmpty())
        }
    }

    @Test fun `session ownership response must be running platform matched and workflow UUID valid`() {
        for (override in listOf(mapOf("id" to WORKFLOW), mapOf("status" to "starting"), mapOf("status" to "RUNNING"), mapOf("platform" to "ios"), mapOf("workflow_run_id" to "../other"), mapOf("workflow_run_id" to null))) {
            LoopbackBackend().use { backend ->
                backend.intercept = { Reply(bytes = backend.session(override)) }
                client(backend).use { connection ->
                    assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                    assertFailsWith<AdapterFailure> { connection.read("hierarchy") }
                }
                assertEquals(1, backend.requests.size)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 307, 308, 401, 403, 404, 408, 410, 429, 500, 501, 503])
    fun `status failures never follow redirects or replay including retry-after zero`(status: Int) {
        LoopbackBackend().use { destination ->
            LoopbackBackend().use { backend ->
                backend.intercept = { Reply(status, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Location" to destination.origin, "Retry-After" to "0")) }
                client(backend).use { connection ->
                    val failure = assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                    assertFalse(failure.message.orEmpty().contains(PRIVATE_SENTINEL))
                    assertFalse(failure.message.orEmpty().contains(FIXTURE_KEY))
                }
                assertEquals(1, backend.requests.size)
                assertTrue(destination.requests.isEmpty())
            }
        }
    }

    @Test fun `body read timeout is bounded and terminal`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { Reply(bytes = backend.session(), delayMs = 1_000) }
            client(backend, backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to "50")).use { connection ->
                val start = System.nanoTime()
                assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900)
            }
            assertEquals(1, backend.requests.size)
        }
    }

    @Test fun `oversized response is rejected without another request`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { Reply(bytes = ByteArray(RevylClient.MAX_RESPONSE_BYTES + 1) { 32 }) }
            client(backend).use { connection ->
                val failure = assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                assertTrue(failure.message.orEmpty().contains("16 MiB"))
            }
            assertEquals(1, backend.requests.size)
        }
    }

    @Test fun `disconnect does not replay`() {
        LoopbackBackend().use { backend ->
            backend.intercept = { Reply(bytes = byteArrayOf(), disconnect = true) }
            client(backend).use { connection -> assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") } }
            assertEquals(1, backend.requests.size)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "null", "not json", "{\"success\":false,\"action\":\"tap\"}", "{\"success\":true,\"action\":\"launch\"}", "{\"success\":true,\"action\":\"tap\",\"error\":\"synthetic-customer-data-not-for-output\"}"])
    fun `action response must confirm action and boolean success`(body: String) {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path.endsWith("/tap")) Reply(bytes = body.toByteArray()) else null }
            client(backend).use { connection ->
                connection.attach(SESSION, "android")
                val failure = assertFailsWith<AdapterFailure> { connection.act("tap", mapOf("x" to 1, "y" to 2)) }
                assertFailsWith<AdapterFailure> { connection.act("tap", mapOf("x" to 1, "y" to 2)) }
                assertFalse(failure.message.orEmpty().contains(PRIVATE_SENTINEL))
            }
            assertEquals(1, backend.mutations.size)
        }
    }

    @Test fun `close and unsupported Driver methods never destroy session or mutate`() {
        LoopbackBackend().use { backend ->
            val connection = client(backend)
            connection.attach(SESSION, "android")
            val driver = RevylDriver(connection, "android")
            for (action in listOf<() -> Unit>({ driver.inputText("secret") }, { driver.stopApp("com.example") }, { driver.clearAppState("com.example") }, { driver.setPermissions("com.example", mapOf("all" to "allow")) }, { driver.setAndroidChromeDevToolsEnabled(true) })) {
                assertFailsWith<AdapterFailure> { action() }
            }
            driver.close()
            driver.close()
            assertTrue(driver.isShutdown())
            assertFailsWith<AdapterFailure> { connection.read("hierarchy") }
            assertEquals(1, backend.requests.size)
            assertTrue(backend.mutations.isEmpty())
            assertFalse(MaestroException::class.java.isAssignableFrom(AdapterFailure::class.java))
        }
    }

    @Test fun `native mappings preserve actual identifiers point geometry and absent states`() {
        LoopbackBackend("ios").use { backend ->
            val hierarchy = NativeHierarchy.parse(backend.hierarchy(), "ios")
            val root = hierarchy.root.children.single()
            assertNull(root.enabled)
            assertNull(root.selected)
            val text = root.children.last()
            assertEquals("name", text.attributes["resource-id"])
            assertEquals("Existing name", text.attributes["text"])
            assertEquals("[10,10][90,40]", text.attributes["bounds"])
            client(backend).use { connection ->
                connection.attach(SESSION, "ios")
                val info = RevylDriver(connection, "ios").deviceInfo()
                assertEquals(300, info.widthPixels)
                assertEquals(100, info.widthGrid)
                assertEquals(200, info.heightGrid)
            }
        }
        LoopbackBackend().use { backend ->
            val button = NativeHierarchy.parse(backend.hierarchy(), "android").root.children.single().children.first()
            assertEquals("com.example:id/continue", button.attributes["resource-id"])
            assertEquals("Continue action", button.attributes["accessibilityText"])
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["<!DOCTYPE hierarchy [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><hierarchy>&x;</hierarchy>", "<bad/>", "<hierarchy><node bounds='[1,1][0,0]'/></hierarchy>", "<hierarchy><node bounds='[0,0][1,1]' enabled='maybe'/></hierarchy>"])
    fun `malformed XML entities geometry and states are rejected safely`(body: String) {
        val failure = assertFailsWith<AdapterFailure> { NativeHierarchy.parse(body.toByteArray(), "android") }
        assertEquals("Revyl returned an invalid native hierarchy.", failure.message)
    }

    @Test fun `PNG corruption truncation chunks and pixel bombs fail closed`() {
        val valid = png(100, 200)
        val variants = listOf(
            valid.copyOf().also { it[32] = (it[32].toInt() xor 1).toByte() },
            valid.copyOf(valid.size - 1),
            valid.copyOf(valid.size - 12),
            valid.copyOf().also { ByteBuffer.wrap(it, 8, 4).putInt(Int.MAX_VALUE) },
            valid.copyOf().also { ByteBuffer.wrap(it, 16, 8).putInt(16_000).putInt(16_000) },
            valid + byteArrayOf(0),
            PRIVATE_SENTINEL.toByteArray(),
        )
        for (bytes in variants) {
            LoopbackBackend().use { backend ->
                backend.intercept = { if (it.path.endsWith("/screenshot")) Reply(bytes = bytes) else null }
                client(backend).use { connection ->
                    connection.attach(SESSION, "android")
                    val driver = RevylDriver(connection, "android")
                    val failure = assertFailsWith<AdapterFailure> { driver.deviceInfo() }
                    assertFalse(failure.message.orEmpty().contains(PRIVATE_SENTINEL))
                    assertFailsWith<AdapterFailure> { driver.tap(maestro.Point(1, 1)) }
                }
                assertTrue(backend.mutations.isEmpty())
                assertEquals(2, backend.requests.size)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "[]", "[{\"frame\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"enabled\":\"true\"}]", "[{\"frame\":{\"x\":0,\"y\":0,\"width\":-1,\"height\":1}}]", "[{\"frame\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"AXUniqueId\":123}]", "[{\"frame\":{\"x\":0,\"y\":0,\"width\":1,\"height\":1},\"children\":{}}]"])
    fun `malformed IDB structure states and identifiers fail closed`(body: String) {
        assertFailsWith<AdapterFailure> { NativeHierarchy.parse(body.toByteArray(), "ios") }
    }

    @Test fun `hierarchy depth and node counts are bounded`() {
        val node = "<node bounds='[0,0][1,1]'>"
        for (xml in listOf("<hierarchy>" + node.repeat(102) + "</node>".repeat(102) + "</hierarchy>", "<hierarchy>" + "<node bounds='[0,0][1,1]'/>".repeat(10_001) + "</hierarchy>")) {
            assertFailsWith<AdapterFailure> { NativeHierarchy.parse(xml.toByteArray(), "android") }
        }
    }
}
