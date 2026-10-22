package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
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

class WebPlatformTest {
    @TempDir lateinit var temporary: Path

    private val entryUrl = "https://example.com/login"
    private val proxy = "/api/v1/execution/device-proxy/$WORKFLOW/"
    private val webHealth = mapOf<String, Any?>(
        "ready" to true, "first_frame_observed" to true, "current_fence" to true, "supports_maestro_web" to true,
    )

    private fun node(text: String = "", id: String? = null, bounds: List<Int> = listOf(0, 0, 100, 200), children: List<Map<String, Any>> = emptyList(), attributes: Map<String, Any> = emptyMap()): Map<String, Any> = mapOf(
        "attributes" to (mapOf("text" to text, "bounds" to listOf("left", "top", "right", "bottom").zip(bounds).toMap()) +
            (id?.let { mapOf("resource-id" to it) } ?: emptyMap()) + attributes),
        "children" to children,
    )

    private fun reply(value: Any?): Reply = Reply(bytes = json.writeValueAsBytes(value))

    private fun serveWeb(
        backend: LoopbackBackend,
        health: Map<String, Any?> = webHealth,
        hierarchy: () -> Any = { node(children = listOf(node("Continue", "continue", listOf(10, 80, 90, 120)))) },
        query: (RecordedRequest) -> Any = { mapOf("nodes" to emptyList<Any>()) },
        action: (RecordedRequest) -> Reply? = { null },
    ) {
        backend.intercept = { request ->
            when {
                request.path == "${proxy}health" -> reply(health)
                request.path == "${proxy}maestro_web_info" && request.method == "GET" -> reply(mapOf(
                    "width_pixels" to 100, "height_pixels" to 200, "width_grid" to 100, "height_grid" to 200,
                ))
                request.path == "${proxy}maestro_web_hierarchy" && request.method == "GET" -> reply(hierarchy())
                request.path == "${proxy}maestro_web_query" && request.method == "POST" -> reply(query(request))
                request.path.startsWith(proxy) && request.method == "POST" -> action(request) ?: reply(mapOf(
                    "success" to true, "action" to request.path.substringAfterLast('/'), "error" to null,
                    "execution_uncertain" to false, "latency_ms" to 1,
                ))
                else -> null
            }
        }
    }

    private fun run(backend: LoopbackBackend, body: String, compat: Boolean = false, config: String = "url: $entryUrl", environment: Map<String, String> = backend.environment): Pair<Int, String> {
        val flow = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(flow, "$config\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val options = if (compat) arrayOf("--maestro-compat") else emptyArray()
        val status = runCli(options + arrayOf("--session", SESSION, "--platform", "web", flow.toString()),
            environment, PrintStream(output), PrintStream(output))
        val text = output.toString()
        assertFalse(text.contains(FIXTURE_KEY))
        assertFalse(text.contains(PRIVATE_SENTINEL))
        assertTrue(backend.requests.none { it.path.contains("worker-connection") }, "Web must not discover a viewer WebSocket")
        return status to text
    }

    private fun calls(backend: LoopbackBackend): List<Pair<String, JsonNode>> = backend.mutations.map {
        it.path.removePrefix(proxy) to json.readTree(it.body)
    }

    private fun expected(vararg actions: Pair<String, String>): List<Pair<String, JsonNode>> = actions.map { it.first to json.readTree(it.second) }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `URL and HTTP appId flows execute login navigation focus input keys back scroll and targeted clearState`(compat: Boolean) {
        for (config in listOf("url: $entryUrl", "appId: $entryUrl")) {
            LoopbackBackend("web").use { backend ->
                var loggedIn = false
                serveWeb(backend, hierarchy = {
                    node(children = if (loggedIn) listOf(node("Welcome", "welcome")) else listOf(
                        node("Username", "username", listOf(10, 10, 90, 30)),
                        node("Password", "password", listOf(10, 40, 90, 60)),
                        node("Login", "login", listOf(10, 80, 90, 120)),
                    ))
                }, action = {
                    if (it.path.endsWith("/maestro_web_tap") && json.readTree(it.body).path("y").intValue() == 100) loggedIn = true
                    null
                })
                val (status, output) = run(backend, """
                    - launchApp
                    - tapOn: {id: username}
                    - inputText: fixture
                    - inputText: '-user'
                    - eraseText: 2
                    - pressKey: Enter
                    - tapOn: {id: password}
                    - inputText: fixture-password
                    - tapOn: Login
                    - assertVisible: Welcome
                    - openLink: https://example.com/account
                    - back
                    - pressKey: Back
                    - scroll
                    - pressKey: Home
                    - clearState: https://example.com/reset
                """.trimIndent(), compat, config)
                assertEquals(0, status, output)
                assertEquals(expected(
                    "navigate" to "{\"url\":\"$entryUrl\"}",
                    "maestro_web_tap" to "{\"x\":50,\"y\":20}",
                    "maestro_web_input" to "{\"text\":\"fixture\"}",
                    "maestro_web_input" to "{\"text\":\"-user\"}",
                    "key" to "{\"key\":\"Backspace\"}", "key" to "{\"key\":\"Backspace\"}",
                    "key" to "{\"key\":\"Enter\"}",
                    "maestro_web_tap" to "{\"x\":50,\"y\":50}",
                    "maestro_web_input" to "{\"text\":\"fixture-password\"}",
                    "maestro_web_tap" to "{\"x\":50,\"y\":100}",
                    "navigate" to "{\"url\":\"https://example.com/account\"}",
                    "back" to "{}", "back" to "{}", "maestro_web_scroll" to "{}",
                    "key" to "{\"key\":\"Home\"}", "maestro_web_clear_state" to "{\"url\":\"https://example.com/reset\"}",
                ), calls(backend))
                assertTrue(backend.requests.all { it.authorization == "Bearer $FIXTURE_KEY" && it.agent == "Maestro" })
                assertTrue(backend.requests.indexOfFirst { it.path == "${proxy}health" } < backend.requests.indexOfFirst { it.method == "POST" })
                assertTrue(backend.requests.none { it.path == "${proxy}hierarchy" })
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `web launch arguments keep their JSON types while native lifecycle permissions and keyboard commands are no-ops`(compat: Boolean) {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend)
            val (status, output) = run(backend, """
                - stopApp
                - killApp
                - setPermissions: {permissions: {camera: deny}}
                - hideKeyboard
                - clearKeychain
                - launchApp:
                    clearState: true
                    arguments: {isE2E: true, retries: 3, ratio: 0.5, name: fixture}
            """.trimIndent(), compat)
            assertEquals(0, status, output)
            assertEquals(expected(
                "maestro_web_clear_state" to "{\"url\":\"$entryUrl\"}",
                "maestro_web_launch" to "{\"url\":\"$entryUrl\",\"arguments\":{\"isE2E\":true,\"retries\":3,\"ratio\":0.5,\"name\":\"fixture\"}}",
            ), calls(backend))
            assertTrue(backend.requests.none { it.path == "${proxy}keyboard" })
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "supports_maestro_web:missing", "supports_maestro_web:false", "supports_maestro_web:string",
        "ready:missing", "ready:false", "ready:string",
        "first_frame_observed:missing", "first_frame_observed:false", "first_frame_observed:string",
        "current_fence:missing", "current_fence:false", "current_fence:string",
    ])
    fun `every web flow requires strict boolean capability readiness first frame and current fence before any operation`(fault: String) {
        val field = fault.substringBefore(':')
        val health = when (fault.substringAfter(':')) {
            "missing" -> webHealth - field
            "false" -> webHealth + (field to false)
            else -> webHealth + (field to "true")
        }
        for (compat in listOf(false, true)) {
            for (body in listOf("- clearKeychain", "- tapOn: {point: '20, 30'}\n- inputText: fixture")) {
                LoopbackBackend("web").use { backend ->
                    serveWeb(backend, health = health)
                    val (status, output) = run(backend, body, compat)
                    assertEquals(1, status, output)
                    assertEquals(listOf("/api/v1/execution/device-sessions/$SESSION", "${proxy}health"), backend.requests.map { it.path })
                    assertTrue(backend.mutations.isEmpty())
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["uncertain", "string-uncertain", "string-success", "wrong-action", "error", "malformed", "http"])
    fun `web action uncertainty latches before Maestro compat retries optional continuation and completion hooks`(fault: String) {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend, action = {
                when (fault) {
                    "malformed" -> Reply(bytes = "not-json-$PRIVATE_SENTINEL".toByteArray())
                    "http" -> Reply(503, PRIVATE_SENTINEL.toByteArray(), headers = mapOf("Retry-After" to "0"))
                    else -> reply(mapOf(
                        "success" to if (fault == "string-success") "true" else true,
                        "action" to if (fault == "wrong-action") "key" else "maestro_web_tap",
                        "execution_uncertain" to when (fault) { "uncertain" -> true; "string-uncertain" -> "false"; else -> false },
                        "error" to if (fault == "error") PRIVATE_SENTINEL else null,
                        "latency_ms" to 1,
                    ))
                }
            })
            val (status, output) = run(backend, """
                - retry:
                    maxRetries: 2
                    commands:
                      - tapOn: {point: '20, 30', optional: true}
                      - inputText: not-sent
                - pressKey: Home
            """.trimIndent(), compat = true, config = "url: $entryUrl\nonFlowComplete: [{pressKey: Backspace}]")
            assertEquals(1, status, output)
            assertEquals(expected("maestro_web_tap" to "{\"x\":20,\"y\":30}"), calls(backend))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["bounds-type", "fractional", "inverted", "overflow", "text-type", "id-type", "state-type", "children-type", "depth"])
    fun `malformed DOM hierarchy fails terminally without a later mutation`(fault: String) {
        val root = json.valueToTree<JsonNode>(node("Continue"))
        val attributes = root.path("attributes") as com.fasterxml.jackson.databind.node.ObjectNode
        val bounds = attributes.path("bounds") as com.fasterxml.jackson.databind.node.ObjectNode
        when (fault) {
            "bounds-type" -> attributes.put("bounds", "[0,0][100,200]")
            "fractional" -> bounds.put("left", 0.5)
            "inverted" -> bounds.put("right", -1)
            "overflow" -> bounds.put("bottom", 2_147_483_648L)
            "text-type" -> attributes.put("text", true)
            "id-type" -> attributes.put("resource-id", 42)
            "state-type" -> attributes.put("synthetic", "true")
            "children-type" -> (root as com.fasterxml.jackson.databind.node.ObjectNode).put("children", false)
        }
        var hierarchy: Any = root
        if (fault == "depth") {
            var nested = node("Continue")
            repeat(102) { nested = node(children = listOf(nested)) }
            hierarchy = nested
        }
        for (compat in listOf(false, true)) {
            LoopbackBackend("web").use { backend ->
                serveWeb(backend, hierarchy = { hierarchy })
                val (status, output) = run(backend, "- assertVisible: Continue\n- tapOn: {point: '20, 30'}", compat)
                assertEquals(1, status, output)
                assertTrue(backend.requests.any { it.path == "${proxy}maestro_web_hierarchy" })
                assertTrue(backend.mutations.isEmpty())
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fixture iframe offsets and Flutter synthetic semantics retain their worker supplied coordinates`(compat: Boolean) {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend, hierarchy = {
                node(children = listOf(
                    node("", "iframe", listOf(20, 30, 100, 150), children = listOf(
                        node("Frame submit", "frame-submit", listOf(30, 50, 90, 90)),
                    )),
                    node("Flutter submit", "flutter-submit", listOf(600_000, 600_000, 600_002, 600_002),
                        attributes = mapOf("synthetic" to true, "ignoreBoundsFiltering" to true)),
                ))
            })
            val (status, output) = run(backend, """
                - assertVisible: Frame submit
                - tapOn: {id: frame-submit}
                - assertVisible: Flutter submit
                - tapOn: {id: flutter-submit}
            """.trimIndent(), compat)
            assertEquals(0, status, output)
            assertEquals(expected(
                "maestro_web_tap" to "{\"x\":60,\"y\":70}",
                "maestro_web_tap" to "{\"x\":600001,\"y\":600001}",
            ), calls(backend))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CSS queries select the matching hierarchy identity over a text decoy while ignoring children`(compat: Boolean) {
        LoopbackBackend("web").use { backend ->
            val target = node("Submit", "css-submit", listOf(20, 120, 80, 160))
            serveWeb(backend, hierarchy = {
                node(children = listOf(
                    node("Submit", "text-decoy", listOf(10, 20, 90, 60)),
                    target + ("children" to listOf(node("Submit icon", "icon", listOf(30, 125, 40, 135)))),
                ))
            }, query = { request ->
                if (json.readTree(request.body).path("css").textValue() == "button[data-test='submit']")
                    mapOf("nodes" to listOf(target))
                else mapOf("nodes" to emptyList<Any>())
            })
            val (status, output) = run(backend, """
                - assertVisible: {css: "button[data-test='submit']"}
                - extendedWaitUntil: {notVisible: {css: '#missing'}, timeout: 1000}
                - tapOn: {css: "button[data-test='submit']"}
            """.trimIndent(), compat, environment = backend.environment + ("REVYL_MAESTRO_FLOW_TIMEOUT_MS" to "60000"))
            assertEquals(0, status, output)
            val queries = backend.mutations.filter { it.path == "${proxy}maestro_web_query" }
            assertTrue(queries.isNotEmpty())
            assertTrue(queries.all { json.readTree(it.body).fieldNames().asSequence().toSet() == setOf("css") })
            assertEquals(setOf("button[data-test='submit']", "#missing"), queries.map { json.readTree(it.body).path("css").textValue() }.toSet())
            assertEquals(expected("maestro_web_tap" to "{\"x\":50,\"y\":140}"), calls(backend).filterNot { it.first == "maestro_web_query" })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `date field input preserves exact supplied text without refocusing clearing or adapter formatting`(compat: Boolean) {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend, hierarchy = { node(children = listOf(node("Date", "date", listOf(10, 10, 90, 30)))) })
            val (status, output) = run(backend, """
                - tapOn: {id: date}
                - inputText: '10222026'
                - inputText: '2026-10-22'
                - inputText: '10/22/2026'
            """.trimIndent(), compat)
            assertEquals(0, status, output)
            assertEquals(expected(
                "maestro_web_tap" to "{\"x\":50,\"y\":20}",
                "maestro_web_input" to "{\"text\":\"10222026\"}",
                "maestro_web_input" to "{\"text\":\"2026-10-22\"}",
                "maestro_web_input" to "{\"text\":\"10/22/2026\"}",
            ), calls(backend))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `web screenshots delegate to the existing private screenshot path`(compat: Boolean) {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend)
            val name = "web-${UUID.randomUUID()}"
            val screenshot = Path.of(".revyl-maestro/$name.png")
            try {
                val (status, output) = run(backend, "- takeScreenshot: $name", compat)
                assertEquals(0, status, output)
                assertTrue(Files.size(screenshot) > 32)
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(screenshot)))
                assertTrue(backend.requests.any { it.path == "${proxy}screenshot" && it.method == "GET" })
                assertTrue(backend.mutations.isEmpty())
            } finally { Files.deleteIfExists(screenshot) }
        }
    }

    @Test fun `compat JavaScript produces web input through the real Orchestra engine`() {
        LoopbackBackend("web").use { backend ->
            serveWeb(backend)
            val (status, output) = run(backend, """
                - evalScript: ${'$'}{output.message = 'fixture-' + (6 * 7)}
                - assertTrue: ${'$'}{maestro.platform === 'web'}
                - inputText: ${'$'}{output.message}
            """.trimIndent(), compat = true)
            assertEquals(0, status, output)
            assertEquals(expected("maestro_web_input" to "{\"text\":\"fixture-42\"}"), calls(backend))
        }
    }
}
