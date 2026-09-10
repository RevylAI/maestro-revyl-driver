package ai.revyl.maestro

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerInputTest {
    @TempDir lateinit var temporary: Path

    private fun run(backend: LoopbackBackend, body: String, timeoutMs: Int = 1000): Pair<Int, String> {
        val file = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(file, "appId: com.example\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val status = runCli(arrayOf("--session", SESSION, "--platform", backend.platform, file.toString()),
            backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to timeoutMs.toString()), PrintStream(output), PrintStream(output))
        return status to output.toString()
    }

    private fun assertPrivateOutput(output: String, backend: LoopbackBackend) {
        for (value in listOf(PRIVATE_SENTINEL, FIXTURE_KEY, "offline-worker-token", backend.viewer.url, backend.origin)) assertFalse(output.contains(value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `backend authorizes discovery but API credentials never reach the worker`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val (status, output) = run(backend, "- inputText: '$PRIVATE_SENTINEL'")
            assertEquals(0, status, output)
            assertEquals("/api/v1/execution/streaming/worker-connection/$WORKFLOW", backend.requests[1].path)
            assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
            val handshake = backend.viewer.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/ws?token=offline-worker-token", handshake.path)
            assertNull(handshake.getHeader("Authorization"))
            assertNull(handshake.getHeader("X-Revyl-Agent-Session-Id"))
            assertEquals(1, backend.viewer.server.requestCount)
            assertTrue(backend.viewer.closed.await(2, TimeUnit.SECONDS))
            assertTrue(backend.mutations.isEmpty())
            assertPrivateOutput(output, backend)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 410, 503, 307])
    fun `denied or unavailable discovery prevents earlier mutations without retry`(status: Int) {
        LoopbackBackend().use { backend ->
            backend.intercept = { if (it.path.contains("/worker-connection/")) Reply(status, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Location" to backend.origin + "/redirect", "Retry-After" to "0")) else null }
            val (exit, output) = run(backend, "- pressKey: Home\n- inputText: hello")
            assertEquals(1, exit)
            assertEquals(2, backend.requests.size)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(0, backend.viewer.server.requestCount)
            assertPrivateOutput(output, backend)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["invalid", "duplicate", "timeout", "disconnect"])
    fun `malformed or interrupted discovery cannot reach earlier flow mutations`(fault: String) {
        LoopbackBackend().use { backend ->
            backend.intercept = { request -> if (!request.path.contains("/worker-connection/")) null else when (fault) {
                "invalid" -> Reply(bytes = PRIVATE_SENTINEL.toByteArray())
                "duplicate" -> Reply(bytes = backend.connection().toString(Charsets.UTF_8).replace("\"status\":\"ready\"", "\"status\":\"not_ready\",\"status\":\"ready\"").toByteArray())
                "timeout" -> Reply(bytes = backend.connection(), delayMs = 1000)
                else -> Reply(bytes = byteArrayOf(), disconnect = true)
            } }
            val (exit, output) = run(backend, "- pressKey: Home\n- inputText: hello", 300)
            assertEquals(1, exit)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(2, backend.requests.size)
            assertEquals(0, backend.viewer.server.requestCount)
            assertPrivateOutput(output, backend)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{\"status\":\"not_ready\"}", "{\"status\":\"stopped\"}", "{\"status\":true}",
        "{\"workflow_run_id\":\"11111111-1111-4111-8111-111111111111\"}", "{\"workflow_run_id\":null}",
        "{\"worker_ws_url\":null}", "{\"worker_ws_url\":42}",
    ])
    fun `discovery requires the ready attached workflow and a string worker URL`(overrides: String) {
        LoopbackBackend().use { backend ->
            backend.connectionOverrides = json.readTree(overrides).fields().asSequence().associate { it.key to json.treeToValue(it.value, Any::class.java) }
            val (exit, output) = run(backend, "- pressKey: Home\n- setClipboard: hello\n- pasteText")
            assertEquals(1, exit)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(0, backend.viewer.server.requestCount)
            assertPrivateOutput(output, backend)
        }
    }

    @ParameterizedTest
    @MethodSource("invalidUrls")
    fun `invalid discovered URLs fail before any worker connection or mutation`(url: String) {
        LoopbackBackend().use { backend ->
            backend.connectionOverrides = mapOf("worker_ws_url" to url)
            val (exit, output) = run(backend, "- pressKey: Home\n- inputText: hello")
            assertEquals(1, exit)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(0, backend.viewer.server.requestCount)
            assertPrivateOutput(output, backend)
        }
    }

    @Test fun `URL validation permits only secure or literal loopback control connections`() {
        listOf("wss://worker.example/ws?token=fixture", "ws://localhost:1234/ws", "ws://127.0.0.1/ws", "ws://[::1]:1234/ws").forEach(ViewerInputChannel::validateUrl)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 307, 503])
    fun `worker handshake errors and redirects cannot forward auth or replay`(status: Int) {
        MockWebServer().use { worker ->
            worker.start(InetAddress.getByName("127.0.0.1"), 0)
            worker.enqueue(MockResponse().setResponseCode(status).setHeader("Location", worker.url("/redirect")).setBody(PRIVATE_SENTINEL))
            LoopbackBackend().use { backend ->
                backend.connectionOverrides = mapOf("worker_ws_url" to worker.url("/ws?token=offline-worker-token").toString().replaceFirst("http:", "ws:"))
                val (exit, output) = run(backend, "- pressKey: Home\n- inputText: hello")
                assertEquals(1, exit)
                assertTrue(backend.mutations.isEmpty())
                assertEquals(1, worker.requestCount)
                assertNull(worker.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertPrivateOutput(output, backend)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["android", "ios"])
    fun `buffered and unrelated ACKs are ignored while exact input ACKs release each action`(platform: String) {
        LoopbackBackend(platform).use { backend ->
            val viewer = backend.viewer
            viewer.onOpen = { it.send("{\"event_type\":\"ACTION_ACK\",\"action\":\"input\",\"action_id\":\"buffered\",\"success\":true}") }
            var previousId: String? = null
            viewer.onInput = { socket, event ->
                viewer.acknowledge(socket, event, mapOf("action_id" to (previousId ?: "unrelated"), "success" to false))
                viewer.acknowledge(socket, event, mapOf("event_type" to "ACTION_RECEIVED"))
                socket.send("{\"event_type\":\"DEVICE_INIT_STATUS\",\"status\":\"ready\"}")
                viewer.acknowledge(socket, event)
                previousId = event.path("action_id").textValue()
            }
            val (status, output) = run(backend, "- inputText: one\n- inputText: two\n- pressKey: Home")
            assertEquals(0, status, output)
            assertEquals(2, viewer.messages.size)
            assertEquals(listOf("go_home"), backend.mutations.map { it.path.substringAfterLast('/') })
        }
    }

    @ParameterizedTest
    @MethodSource("uncertainInputs")
    fun `uncertain viewer input latches terminal failure without replay or later Orchestra mutations`(platform: String, fault: String) {
        LoopbackBackend(platform).use { backend ->
            val viewer = backend.viewer
            viewer.onInput = { socket, event -> when (fault) {
                "receipt" -> viewer.acknowledge(socket, event, mapOf("event_type" to "ACTION_RECEIVED"))
                "nonmatching" -> viewer.acknowledge(socket, event, mapOf("action_id" to UUID.randomUUID().toString()))
                "numeric_id" -> viewer.acknowledge(socket, event, mapOf("action_id" to 1))
                "negative" -> viewer.acknowledge(socket, event, mapOf("success" to false, "error" to PRIVATE_SENTINEL))
                "string_success" -> viewer.acknowledge(socket, event, mapOf("success" to "true"))
                "numeric_success" -> viewer.acknowledge(socket, event, mapOf("success" to 1))
                "null_success" -> viewer.acknowledge(socket, event, mapOf("success" to null))
                "missing_success" -> socket.send("{\"event_type\":\"ACTION_ACK\",\"action\":\"input\",\"action_id\":\"${event.path("action_id").textValue()}\"}")
                "wrong_action" -> viewer.acknowledge(socket, event, mapOf("action" to "tap"))
                "error" -> viewer.acknowledge(socket, event, mapOf("error" to PRIVATE_SENTINEL))
                "error_code" -> viewer.acknowledge(socket, event, mapOf("error_code" to PRIVATE_SENTINEL))
                "invalid" -> socket.send(PRIVATE_SENTINEL)
                "duplicate" -> socket.send("{\"event_type\":\"ACTION_ACK\",\"action\":\"input\",\"action_id\":\"${event.path("action_id").textValue()}\",\"success\":false,\"success\":true}")
                "binary" -> socket.send(PRIVATE_SENTINEL.encodeUtf8())
                "oversized" -> socket.send("x".repeat(65537))
                "invalid_ping" -> socket.send("{\"type\":\"ping\",\"id\":\"${"x".repeat(129)}\"}")
                "disconnect" -> viewer.disconnect()
                "close" -> socket.close(1000, PRIVATE_SENTINEL)
                "timeout" -> Unit
            } }
            val (status, output) = run(backend, "- inputText: '$PRIVATE_SENTINEL'\n- inputText: second\n- pressKey: Home", 500)
            assertEquals(1, status, fault)
            assertEquals(1, viewer.messages.size, fault)
            assertTrue(backend.mutations.isEmpty())
            assertEquals(1, viewer.server.requestCount)
            assertTrue(viewer.closed.await(2, TimeUnit.SECONDS))
            assertPrivateOutput(output, backend)
        }
    }

    @Test fun `server application ping receives only a bounded pong with matching id`() {
        LoopbackBackend().use { backend ->
            backend.viewer.onOpen = { it.send("{\"type\":\"ping\",\"id\":\"0123456789abcdef\",\"timestamp\":123}") }
            val (status, output) = run(backend, "- inputText: hello")
            assertEquals(0, status, output)
            assertEquals(json.readTree("{\"type\":\"pong\",\"id\":\"0123456789abcdef\"}"), backend.viewer.messages.single { it.path("type").textValue() == "pong" })
            assertEquals(1, backend.viewer.messages.count { it.path("action").textValue() == "MANUAL_INPUT" })
        }
    }

    @Test fun `direct driver input requires attachment established connection and the attached platform`() {
        LoopbackBackend().use { backend ->
            fun client() = RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10))
            client().use { connection ->
                assertFailsWith<AdapterFailure> { RevylDriver(connection, "android").inputText("hello") }
                assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
                assertTrue(backend.requests.isEmpty())
            }
            client().use { connection ->
                connection.attach(SESSION, "android")
                assertFailsWith<AdapterFailure> { RevylDriver(connection, "android").inputText("hello") }
                assertFailsWith<AdapterFailure> { connection.establishViewerInput() }
            }
            client().use { connection ->
                connection.attach(SESSION, "android")
                connection.establishViewerInput()
                assertFailsWith<AdapterFailure> { RevylDriver(connection, "ios").inputText("hello") }
                assertFailsWith<AdapterFailure> { RevylDriver(connection, "android").inputText("hello") }
            }
            assertTrue(backend.mutations.isEmpty())
            assertTrue(backend.viewer.messages.isEmpty())
        }
    }

    @Test fun `direct callers cannot mutate reconnect or resend after missing ACK`() {
        LoopbackBackend().use { backend ->
            backend.viewer.onInput = { _, _ -> }
            val settings = ConnectionSettings.fromEnvironment(backend.environment + ("REVYL_MAESTRO_REQUEST_TIMEOUT_MS" to "300"))
            RevylClient(settings, System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).use { connection ->
                connection.attach(SESSION, "android")
                connection.establishViewerInput()
                val driver = RevylDriver(connection, "android")
                assertFailsWith<AdapterFailure> { driver.inputText("hello") }
                assertFailsWith<AdapterFailure> { driver.inputText("again") }
                assertFailsWith<AdapterFailure> { connection.act("go_home", emptyMap()) }
                assertFailsWith<AdapterFailure> { connection.establishViewerInput() }
                assertFailsWith<AdapterFailure> { connection.attach(SESSION, "android") }
            }
            assertEquals(1, backend.viewer.messages.size)
            assertEquals(1, backend.viewer.server.requestCount)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `closing a pending input cancels only local resources and prevents later mutation`() {
        LoopbackBackend().use { backend ->
            val receivedInput = CountDownLatch(1)
            backend.viewer.onInput = { _, _ -> receivedInput.countDown() }
            val executor = Executors.newSingleThreadExecutor()
            try {
                RevylClient(ConnectionSettings.fromEnvironment(backend.environment), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)).use { connection ->
                    connection.attach(SESSION, "android")
                    connection.establishViewerInput()
                    val pending = executor.submit<Unit> { RevylDriver(connection, "android").inputText("hello") }
                    assertTrue(receivedInput.await(2, TimeUnit.SECONDS))
                    connection.close()
                    assertTrue(assertFailsWith<ExecutionException> { pending.get(2, TimeUnit.SECONDS) }.cause is AdapterFailure)
                    assertFailsWith<AdapterFailure> { connection.act("go_home", emptyMap()) }
                    assertFailsWith<AdapterFailure> { RevylDriver(connection, "android").inputText("again") }
                    assertTrue(backend.viewer.closed.await(2, TimeUnit.SECONDS))
                }
            } finally { executor.shutdownNow() }
            assertEquals(1, backend.viewer.messages.size)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    @Test fun `ack deadline is bounded by the total flow deadline even for direct callers`() {
        LoopbackBackend().use { backend ->
            backend.viewer.onInput = { _, _ -> }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            RevylClient(ConnectionSettings.fromEnvironment(backend.environment), deadline).use { connection ->
                connection.attach(SESSION, "android")
                connection.establishViewerInput()
                assertFailsWith<AdapterFailure> { RevylDriver(connection, "android").inputText("hello") }
                assertTrue(System.nanoTime() - deadline < TimeUnit.SECONDS.toNanos(1))
                assertFailsWith<AdapterFailure> { connection.act("go_home", emptyMap()) }
            }
            assertEquals(1, backend.viewer.messages.size)
            assertTrue(backend.mutations.isEmpty())
        }
    }

    companion object {
        @JvmStatic fun invalidUrls(): Stream<Arguments> = listOf(
            "", "/ws", "https://worker.example/ws", "ws://worker.example/ws", "ws://127.0.0.1.example/ws",
            "ws://127.1/ws", "ws://2130706433/ws", "wss://fixture:secret@worker.example/ws",
            "ws://127.0.0.1/ws#fragment", "wss://worker.example:0/ws", "wss://worker.example:65536/ws",
            "wss:///ws", "wss://worker.example/ws\n", "wss://worker.example/" + "x".repeat(8192),
        ).map { Arguments.of(it) }.stream()

        @JvmStatic fun uncertainInputs(): Stream<Arguments> = listOf("android", "ios").flatMap { platform ->
            listOf("receipt", "nonmatching", "numeric_id", "negative", "string_success", "numeric_success", "null_success", "missing_success", "wrong_action", "error", "error_code", "invalid", "duplicate", "binary", "oversized", "invalid_ping", "disconnect", "close", "timeout").map { Arguments.of(platform, it) }
        }.stream()
    }
}
