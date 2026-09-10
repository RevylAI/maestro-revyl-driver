package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ServerSocketFactory

internal class LoopbackViewer : Closeable {
    val server = MockWebServer()
    val messages = CopyOnWriteArrayList<JsonNode>()
    val closed = CountDownLatch(1)
    var onOpen: (WebSocket) -> Unit = {}
    var onInput: (WebSocket, JsonNode) -> Unit = { socket, event -> acknowledge(socket, event) }
    @Volatile var socket: WebSocket? = null
    @Volatile private var transportSocket: Socket? = null
    val url: String get() = server.url("/ws?token=offline-worker-token").toString().replaceFirst("http:", "ws:")

    init {
        server.serverSocketFactory = object : ServerSocketFactory() {
            override fun createServerSocket(): ServerSocket = object : ServerSocket() {
                override fun accept(): Socket = super.accept().also { transportSocket = it }
            }
            override fun createServerSocket(port: Int): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(port)) }
            override fun createServerSocket(port: Int, backlog: Int): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(port), backlog) }
            override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(address, port), backlog) }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                onOpen(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = json.readTree(text)
                messages.add(event)
                if (event.path("action").textValue() == "MANUAL_INPUT") onInput(webSocket, event)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); closed.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        }))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    fun acknowledge(socket: WebSocket, event: JsonNode, overrides: Map<String, Any?> = emptyMap()) {
        socket.send(json.writeValueAsString(mapOf("event_type" to "ACTION_ACK", "action" to "input", "action_id" to event.path("action_id").textValue(), "success" to true) + overrides))
    }

    fun disconnect() { transportSocket?.close() }

    override fun close() { server.shutdown() }
}
