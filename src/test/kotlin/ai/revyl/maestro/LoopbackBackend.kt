package ai.revyl.maestro

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

internal const val SESSION = "11111111-1111-4111-8111-111111111111"
internal const val WORKFLOW = "22222222-2222-4222-8222-222222222222"
internal const val FIXTURE_KEY = "offline-fixture-key"
internal const val PRIVATE_SENTINEL = "synthetic-customer-data-not-for-output"

internal data class RecordedRequest(val method: String, val path: String, val body: String, val authorization: String?, val agent: String?)
internal data class Reply(val status: Int = 200, val bytes: ByteArray, val delayMs: Long = 0, val headers: Map<String, String> = emptyMap(), val disconnect: Boolean = false)

internal class LoopbackBackend(val platform: String = "android") : Closeable {
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "loopback-revyl").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var intercept: (RecordedRequest) -> Reply? = { null }
    private var tapped = false
    val origin: String get() = "http://127.0.0.1:${server.address.port}"
    val environment: Map<String, String> get() = mapOf("REVYL_API_KEY" to FIXTURE_KEY, "REVYL_MAESTRO_API_URL" to origin, "REVYL_MAESTRO_FLOW_TIMEOUT_MS" to "20000")
    val mutations: List<RecordedRequest> get() = requests.filter { it.method != "GET" }

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val request = RecordedRequest(exchange.requestMethod, exchange.requestURI.path, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8), exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Revyl-Agent"))
                requests.add(request)
                val reply = intercept(request) ?: when {
                    request.authorization != "Bearer $FIXTURE_KEY" -> Reply(401, PRIVATE_SENTINEL.toByteArray())
                    request.path == "/api/v1/execution/device-sessions/$SESSION" && request.method == "GET" -> Reply(bytes = session())
                    request.path == "/api/v1/execution/device-proxy/$WORKFLOW/hierarchy" && request.method == "GET" -> Reply(bytes = hierarchy())
                    request.path == "/api/v1/execution/device-proxy/$WORKFLOW/screenshot" && request.method == "GET" -> Reply(bytes = png(if (platform == "ios") 300 else 100, if (platform == "ios") 600 else 200))
                    request.path == "/api/v1/execution/device-proxy/$WORKFLOW/tap" && request.method == "POST" -> {
                        tapped = true
                        Reply(bytes = "{\"success\":true,\"action\":\"tap\"}".toByteArray())
                    }
                    request.path == "/api/v1/execution/device-proxy/$WORKFLOW/launch" && request.method == "POST" -> Reply(bytes = "{\"success\":true,\"action\":\"launch\"}".toByteArray())
                    else -> Reply(404, PRIVATE_SENTINEL.toByteArray())
                }
                if (reply.disconnect) { exchange.close(); return@createContext }
                reply.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                exchange.sendResponseHeaders(reply.status, reply.bytes.size.toLong())
                if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                exchange.responseBody.use { it.write(reply.bytes) }
            } catch (_: Exception) {
                exchange.close()
            }
        }
        server.start()
    }

    fun session(overrides: Map<String, Any?> = emptyMap()): ByteArray = json.writeValueAsBytes(mapOf("id" to SESSION, "workflow_run_id" to WORKFLOW, "status" to "running", "platform" to platform) + overrides)

    fun hierarchy(): ByteArray {
        val text = if (tapped) "Welcome" else "Continue"
        if (platform == "android") return """
            <hierarchy rotation="0"><node bounds="[0,0][100,200]" class="android.widget.FrameLayout" enabled="true">
            <node bounds="[10,80][90,120]" resource-id="com.example:id/continue" content-desc="Continue action" text="$text" class="android.widget.Button" enabled="true" clickable="true"/>
            <node bounds="[10,10][90,40]" resource-id="com.example:id/name" text="Existing name" class="android.widget.EditText" enabled="true" focused="true"/>
            </node></hierarchy>
        """.trimIndent().toByteArray()
        return json.writeValueAsBytes(listOf(mapOf(
            "type" to "Application", "frame" to mapOf("x" to 0, "y" to 0, "width" to 100, "height" to 200),
            "children" to listOf(
                mapOf("type" to "Button", "AXUniqueId" to "continue", "AXLabel" to text, "enabled" to true, "frame" to mapOf("x" to 10, "y" to 80, "width" to 80, "height" to 40)),
                mapOf("type" to "TextField", "AXUniqueId" to "name", "AXLabel" to "Name", "AXValue" to "Existing name", "enabled" to true, "focused" to true, "frame" to mapOf("x" to 10, "y" to 10, "width" to 80, "height" to 30)),
            ),
        )))
    }

    override fun close() { server.stop(0); executor.shutdownNow() }
}

internal fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use {
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    it.toByteArray()
}
