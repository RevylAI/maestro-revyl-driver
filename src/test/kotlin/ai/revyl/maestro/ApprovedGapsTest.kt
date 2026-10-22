package ai.revyl.maestro

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
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovedGapsTest {
    @TempDir lateinit var temporary: Path

    private fun run(backend: LoopbackBackend, compat: Boolean, body: String, configuration: String = ""): Pair<Int, String> {
        val flow = temporary.resolve("${UUID.randomUUID()}.yaml")
        Files.writeString(flow, "appId: com.example\n$configuration\n---\n$body\n")
        val output = ByteArrayOutputStream()
        val args = (if (compat) arrayOf("--maestro-compat") else emptyArray()) +
            arrayOf("--session", SESSION, "--platform", backend.platform, flow.toString())
        val status = runCli(args, backend.environment, PrintStream(output), PrintStream(output))
        val text = output.toString()
        assertFalse(text.contains(FIXTURE_KEY), "CLI disclosed fixture credentials")
        assertFalse(text.contains(PRIVATE_SENTINEL), "CLI disclosed fixture response content")
        return status to text
    }

    private fun assertMutations(backend: LoopbackBackend, expected: List<Pair<String, Map<String, Any>>>) {
        assertEquals(expected.map { "/api/v1/execution/device-proxy/$WORKFLOW/${it.first}" }, backend.mutations.map { it.path })
        expected.zip(backend.mutations).forEach { (expectedCall, request) ->
            assertEquals("POST", request.method)
            assertEquals(json.valueToTree(expectedCall.second), json.readTree(request.body), expectedCall.first)
        }
    }

    private fun capability(backend: LoopbackBackend, name: String, value: String) {
        backend.healthOverrides = if (value == "missing") backend.healthOverrides - name
        else backend.healthOverrides + (name to json.readValue(value, Any::class.java))
    }

    private fun assertPreMutationGate(backend: LoopbackBackend, status: Int, output: String) {
        assertEquals(1, status, output)
        assertTrue(output.contains("does not advertise"), output)
        assertTrue(backend.mutations.isEmpty(), "A capability failure executed an earlier mutation")
        assertEquals(listOf("/api/v1/execution/device-sessions/$SESSION", "/api/v1/execution/device-proxy/$WORKFLOW/health"), backend.requests.map { it.path })
    }

    private fun webHierarchy(backend: LoopbackBackend, payload: String = validWebNodes) {
        backend.intercept = { request ->
            when {
                request.path.endsWith("/hierarchy") -> Reply(bytes = webNativeHierarchy.toByteArray())
                request.path.endsWith("/webview_hierarchy") -> Reply(bytes = (if (payload.trimStart().startsWith("[")) "{\"nodes\":$payload}" else payload).toByteArray())
                else -> null
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Android killApp preserves process death and exact app targeting`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_process_death_kill" to true)
            val (status, output) = run(backend, compat, "- killApp\n- killApp: com.other.app")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("com.example", "com.other.app").map {
                "kill_app" to mapOf("bundle_id" to it, "mode" to "process_death")
            })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `iOS killApp remains an ordinary targeted lifecycle terminate`(compat: Boolean) {
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = mapOf("supports_app_lifecycle" to true)
            val (status, output) = run(backend, compat, "- killApp\n- killApp: com.other.app")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("com.example", "com.other.app").map { "kill_app" to mapOf("bundle_id" to it) })
        }
    }

    @ParameterizedTest
    @MethodSource("allPermissionCases")
    fun `iOS all permissions remain usable regardless of extension advertisement`(compat: Boolean, value: String) {
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = mapOf("supports_app_lifecycle" to true)
            capability(backend, "supports_extended_ios_permissions", value)
            val (status, output) = run(backend, compat, """
                - setPermissions: {permissions: {all: deny}}
                - clearState
                - launchApp
            """.trimIndent())
            assertEquals(0, status, output)
            assertMutations(backend, listOf(
                "set_permissions" to mapOf("bundle_id" to "com.example", "permissions" to mapOf("all" to "deny")),
                "clear_app_state" to mapOf("bundle_id" to "com.example"),
                "set_permissions" to mapOf("bundle_id" to "com.example", "permissions" to mapOf("all" to "unset")),
                "set_permissions" to mapOf("bundle_id" to "com.example", "permissions" to mapOf("all" to "allow")),
                "kill_app" to mapOf("bundle_id" to "com.example"),
                "launch" to mapOf("bundle_id" to "com.example"),
            ))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `iOS extended permissions are forwarded exactly for standalone and launch commands`(compat: Boolean) {
        val permissions = linkedMapOf("camera" to "allow", "notifications" to "deny", "health" to "unset", "homekit" to "allow", "speech" to "deny", "userTracking" to "unset")
        val yaml = permissions.entries.joinToString { "${it.key}: ${it.value}" }
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = mapOf("supports_app_lifecycle" to true, "supports_extended_ios_permissions" to true)
            val (status, output) = run(backend, compat, "- setPermissions: {permissions: {$yaml}}\n- launchApp: {stopApp: false, permissions: {$yaml}}")
            assertEquals(0, status, output)
            val permissionCall = "set_permissions" to mapOf("bundle_id" to "com.example", "permissions" to permissions)
            assertMutations(backend, listOf(permissionCall, permissionCall, "launch" to mapOf("bundle_id" to "com.example")))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `iOS preserves all four exact orientations`(compat: Boolean) {
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = mapOf("supports_precise_orientation" to true)
            val names = listOf("PORTRAIT", "LANDSCAPE_LEFT", "LANDSCAPE_RIGHT", "UPSIDE_DOWN")
            val (status, output) = run(backend, compat, names.joinToString("\n") { "- setOrientation: $it" })
            assertEquals(0, status, output)
            assertMutations(backend, names.map { "set_orientation" to mapOf("orientation" to it.lowercase()) })
        }
    }

    @ParameterizedTest
    @MethodSource("capabilityFailures")
    fun `missing false and string capability flags prevent even an earlier key`(compat: Boolean, platform: String, command: String, flag: String, value: String) {
        LoopbackBackend(platform).use { backend ->
            backend.healthOverrides = flags.associateWith { true }
            capability(backend, flag, value)
            val (status, output) = run(backend, compat, "- pressKey: Enter\n$command")
            assertPreMutationGate(backend, status, output)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Android devtools nodes support nested text and id selectors with exact native tap centers`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend)
            val (status, output) = run(backend, compat, """
                - assertVisible: Web Continue
                - assertVisible: {id: web-submit}
                - tapOn: Web Continue
                - tapOn: {id: web-submit}
            """.trimIndent(), "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("tap" to mapOf("x" to 40, "y" to 60), "tap" to mapOf("x" to 65, "y" to 140)))
            val reads = backend.requests.filter { it.path.endsWith("/webview_hierarchy") }
            assertTrue(reads.isNotEmpty(), "DevTools nodes were not fetched")
            assertTrue(reads.all { it.method == "GET" && it.body.isEmpty() && it.authorization == "Bearer $FIXTURE_KEY" })
        }
    }

    @ParameterizedTest
    @MethodSource("webCapabilityFailures")
    fun `WebView capability gate runs before an earlier mutation`(compat: Boolean, value: String) {
        LoopbackBackend().use { backend ->
            capability(backend, "supports_webview_hierarchy", value)
            webHierarchy(backend)
            val (status, output) = run(backend, compat, "- pressKey: Enter\n- assertVisible: Web Continue", "androidWebViewHierarchy: devtools")
            assertPreMutationGate(backend, status, output)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fractional WebView screen pixels are rounded into native selector bounds`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend, """[{"text":"Fractional target","id":"fractional","bounds":{"x":20.6,"y":40.6,"width":40.2,"height":40.2},"children":[]}]""")
            val (status, output) = run(backend, compat, "- tapOn: {id: fractional}", "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("tap" to mapOf("x" to 41, "y" to 61)))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `upstream merge adds the DOM id to matching native text and preserves native tap bounds`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend, """[{"text":"Native label","id":"dom-native-label","bounds":{"x":20,"y":176,"width":40,"height":10},"children":[]}]""")
            val (status, output) = run(backend, compat, "- assertVisible: {id: dom-native-label}\n- tapOn: {id: dom-native-label}", "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertMutations(backend, listOf("tap" to mapOf("x" to 50, "y" to 185)))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an empty valid WebView list preserves native assertions and taps`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend, "[]")
            val (status, output) = run(backend, compat, "- assertVisible: Native label\n- tapOn: Native label\n- pressKey: Enter", "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertTrue(backend.requests.any { it.path.endsWith("/webview_hierarchy") })
            assertMutations(backend, listOf("tap" to mapOf("x" to 50, "y" to 185), "key" to mapOf("key" to "ENTER")))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `nonvisible WebView rectangles cannot match but their valid descendants remain selectable`(compat: Boolean) {
        val dimensions = listOf(0 to 40, -40 to 40, 40 to -40)
        val nodes = dimensions.mapIndexed { index, (width, height) ->
            mapOf(
                "text" to "Hidden $index", "id" to "hidden-$index",
                "bounds" to mapOf("x" to 20, "y" to 40, "width" to width, "height" to height),
                "children" to listOf(mapOf(
                    "text" to "Visible child $index", "id" to "child-$index",
                    "bounds" to mapOf("x" to 20, "y" to 40, "width" to 40, "height" to 40),
                    "children" to emptyList<Any>(),
                )),
            )
        }
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend, json.writeValueAsString(nodes))
            val commands = dimensions.indices.joinToString("\n") { index ->
                "- assertNotVisible: Hidden $index\n- assertNotVisible: {id: hidden-$index}\n- assertVisible: Visible child $index\n- tapOn: {id: child-$index}"
            }
            val (status, output) = run(backend, compat, commands, "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertMutations(backend, List(3) { "tap" to mapOf("x" to 40, "y" to 60) })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `devtools does not fetch a WebView hierarchy when the native tree has no WebView`(compat: Boolean) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            val (status, output) = run(backend, compat, "- assertVisible: Continue\n- tapOn: Continue", "androidWebViewHierarchy: devtools")
            assertEquals(0, status, output)
            assertFalse(backend.requests.any { it.path.endsWith("/webview_hierarchy") })
            assertMutations(backend, listOf("tap" to mapOf("x" to 50, "y" to 100)))
        }
    }

    @ParameterizedTest
    @MethodSource("nativeConfigurations")
    fun `disabled and native hierarchy modes never call the WebView route`(compat: Boolean, configuration: String) {
        LoopbackBackend().use { backend ->
            webHierarchy(backend)
            val (status, output) = run(backend, compat, "- assertVisible: Native label\n- pressKey: Enter", configuration)
            assertEquals(0, status, output)
            assertFalse(backend.requests.any { it.path.endsWith("/webview_hierarchy") || it.path.endsWith("/health") })
            assertMutations(backend, listOf("key" to mapOf("key" to "ENTER")))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `devtools configuration on iOS is rejected before earlier mutations`(compat: Boolean) {
        LoopbackBackend("ios").use { backend ->
            backend.healthOverrides = flags.associateWith { true }
            val (status, output) = run(backend, compat, "- pressKey: Enter", "androidWebViewHierarchy: devtools")
            assertEquals(1, status, output)
            assertTrue(backend.mutations.isEmpty())
            assertFalse(backend.requests.any { it.path.endsWith("/webview_hierarchy") })
        }
    }

    @ParameterizedTest
    @MethodSource("malformedWebNodes")
    fun `malformed WebView nodes latch failure without retries or later mutations`(compat: Boolean, payload: String) {
        LoopbackBackend().use { backend ->
            backend.healthOverrides = mapOf("supports_webview_hierarchy" to true)
            webHierarchy(backend, payload)
            val (status, output) = run(backend, compat, "- assertVisible: Native label\n- pressKey: Enter", "androidWebViewHierarchy: devtools")
            assertEquals(1, status, output)
            assertTrue(backend.mutations.isEmpty(), "Malformed WebView data allowed continuation")
            assertEquals(1, backend.requests.count { it.path.endsWith("/webview_hierarchy") })
        }
    }

    companion object {
        private val flags = setOf("supports_app_lifecycle", "supports_precise_orientation", "supports_process_death_kill", "supports_extended_ios_permissions", "supports_webview_hierarchy")
        private val deniedFlags = listOf("missing", "false", "\"true\"")
        private val modes = listOf(false, true)
        private val webNativeHierarchy = """
            <hierarchy rotation="0"><node bounds="[0,0][100,200]" class="android.widget.FrameLayout" enabled="true">
            <node bounds="[0,0][100,200]" class="android.webkit.WebView" enabled="true"/>
            <node bounds="[5,175][95,195]" text="Native label" class="android.widget.TextView" enabled="true"/>
            </node></hierarchy>
        """.trimIndent()
        private const val validWebNodes = """[{"text":"","id":"","bounds":{"x":0,"y":0,"width":100,"height":170},"children":[{"text":"Web Continue","id":"web-continue","bounds":{"x":20,"y":40,"width":40,"height":40},"children":[]},{"text":"Web Submit","id":"web-submit","bounds":{"x":40,"y":120,"width":50,"height":40},"children":[]}]}]"""

        @JvmStatic fun allPermissionCases(): Stream<Arguments> = modes.flatMap { compat ->
            (deniedFlags + "true").map { Arguments.of(compat, it) }
        }.stream()

        @JvmStatic fun capabilityFailures(): Stream<Arguments> = modes.flatMap { compat ->
            listOf(
                Triple("android", "- killApp", "supports_process_death_kill"),
                Triple("ios", "- killApp", "supports_app_lifecycle"),
                Triple("ios", "- launchApp", "supports_app_lifecycle"),
                Triple("ios", "- setPermissions: {permissions: {all: allow}}", "supports_app_lifecycle"),
            ).plus(listOf("PORTRAIT", "LANDSCAPE_LEFT", "LANDSCAPE_RIGHT", "UPSIDE_DOWN").map {
                Triple("ios", "- setOrientation: $it", "supports_precise_orientation")
            }).plus(listOf("camera", "notifications", "health", "homekit", "speech", "userTracking").flatMap {
                listOf(
                    Triple("ios", "- setPermissions: {permissions: {$it: allow}}", "supports_extended_ios_permissions"),
                    Triple("ios", "- launchApp: {clearState: true, clearKeychain: true, permissions: {$it: allow}}", "supports_extended_ios_permissions"),
                )
            }).flatMap { (platform, command, flag) -> deniedFlags.map { Arguments.of(compat, platform, command, flag, it) } }
        }.stream()

        @JvmStatic fun webCapabilityFailures(): Stream<Arguments> = modes.flatMap { compat -> deniedFlags.map { Arguments.of(compat, it) } }.stream()

        @JvmStatic fun nativeConfigurations(): Stream<Arguments> = modes.flatMap { compat ->
            listOf("", "androidWebViewHierarchy: native").map { Arguments.of(compat, it) }
        }.stream()

        @JvmStatic fun malformedWebNodes(): Stream<Arguments> {
            val bounds = """{"x":20,"y":40,"width":40,"height":40}"""
            val node = """{"text":"$PRIVATE_SENTINEL","id":"web-id","bounds":$bounds,"children":[]}"""
            var deep = node
            repeat(102) { deep = """{"text":"","id":"","bounds":{"x":0,"y":0,"width":100,"height":170},"children":[$deep]}""" }
            val invalid = listOf(
                "{}", "null", "{\"nodes\":[],\"extra\":1}", "{\"nodes\":{}}", "[null]", "[3]", "[$deep]",
            ) + listOf(
                node.replace(bounds, """{"x":20,"y":40,"width":40}"""),
                node.replace(bounds, """[20,40,60,80]"""),
                node.replace(bounds, """{"x":"20","y":40,"width":40,"height":40}"""),
                node.replace(bounds, """{"x":null,"y":40,"width":40,"height":40}"""),
                node.replace(bounds, """{"x":1e999,"y":40,"width":40,"height":40}"""),
                node.replace(bounds, """{"x":20,"y":40,"width":100001,"height":40}"""),
                node.replace("\"$PRIVATE_SENTINEL\"", "true"),
                node.replace("\"web-id\"", "123"),
                node.replace("\"children\":[]", "\"children\":{}"),
                node.replace("\"children\":[]", "\"children\":null"),
            ).map { "[$it]" }
            return modes.flatMap { compat -> invalid.map { Arguments.of(compat, it) } }.stream()
        }
    }
}
