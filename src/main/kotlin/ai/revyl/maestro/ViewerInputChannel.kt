package ai.revyl.maestro

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class ViewerInputChannel(
    private val timeoutMs: Long,
    private val flowDeadlineNanos: Long,
    private val latchFailure: (AdapterFailure) -> Unit,
) : Closeable {
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var socket: WebSocket? = null
    private var opened = false
    private var closed = false
    private var failure: AdapterFailure? = null
    private var pendingActionId: String? = null
    private var acknowledged = false
    private var receivedMessages = 0

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = lock.withLock {
            if (closed || failure != null) webSocket.cancel() else opened = true
            changed.signalAll()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = lock.withLock {
            if (closed || failure != null) return@withLock
            if (text.length > MAX_MESSAGE_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES || ++receivedMessages > MAX_RECEIVED_MESSAGES) {
                fail()
                return@withLock
            }
            val event = try { RevylClient.readJson(text.toByteArray(Charsets.UTF_8)) } catch (_: AdapterFailure) {
                fail()
                return@withLock
            }
            if (!event.isObject) { fail(); return@withLock }
            if (event.path("type").textValue() == "ping") {
                val id = event.path("id").textValue()
                if (id == null || !PING_ID.matches(id)) { fail(); return@withLock }
                send(json.writeValueAsString(mapOf("type" to "pong", "id" to id)))
                return@withLock
            }
            if (event.path("event_type").textValue() != "ACTION_ACK" || pendingActionId == null ||
                event.path("action_id").textValue() != pendingActionId) return@withLock
            if (event.path("action").textValue() != "input" || !event.path("success").isBoolean ||
                !event.path("success").booleanValue() ||
                (event.has("error") && !event.path("error").isNull) ||
                (event.has("error_code") && !event.path("error_code").isNull)) {
                fail()
                return@withLock
            }
            acknowledged = true
            changed.signalAll()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = lock.withLock { if (!closed) fail() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = lock.withLock { if (!closed) fail() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lock.withLock { if (!closed) fail() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lock.withLock { if (!closed) fail() }
    }

    fun connect(url: String) = lock.withLock {
        checkHealthy()
        requireAdapter(socket == null, "The local control connection cannot reconnect.")
        val deadline = operationDeadline()
        try {
            socket = http.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (_: Exception) {
            fail()
        }
        await(deadline) { opened }
    }

    fun inputText(text: String, paste: Boolean) = lock.withLock {
        checkHealthy()
        requireAdapter(opened && pendingActionId == null, "The local control connection is not ready for input.")
        val deadline = operationDeadline()
        val actionId = UUID.randomUUID().toString()
        val payload = linkedMapOf<String, Any>(
            "event_type" to "STREAM", "action" to "MANUAL_INPUT", "action_id" to actionId,
            "client_sent_at_ms" to System.currentTimeMillis(), "value" to text,
            "incremental" to true, "skip_tap" to true, "clear_first" to false,
        )
        if (paste) payload["paste"] = true
        pendingActionId = actionId
        acknowledged = false
        send(json.writeValueAsString(payload))
        await(deadline) { acknowledged }
        pendingActionId = null
        acknowledged = false
    }

    private fun send(message: String) {
        try {
            val connection = socket
            if (connection == null || connection.queueSize() + message.toByteArray(Charsets.UTF_8).size > MAX_QUEUE_BYTES ||
                !connection.send(message)) fail()
        } catch (_: Exception) {
            fail()
        }
    }

    private fun operationDeadline(): Long {
        val deadline = minOf(flowDeadlineNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        if (deadline <= System.nanoTime()) { fail(); checkHealthy() }
        return deadline
    }

    private fun await(deadlineNanos: Long, complete: () -> Boolean) {
        while (true) {
            checkHealthy()
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) { fail(); checkHealthy() }
            if (complete()) return
            try { changed.awaitNanos(remaining) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail()
            }
        }
    }

    private fun checkHealthy() {
        failure?.let { throw it }
        if (closed || Thread.currentThread().isInterrupted) { fail(); throw failure!! }
    }

    private fun fail() {
        if (failure == null) {
            failure = AdapterFailure("Revyl control input was not confirmed or the connection became unavailable. Input may have executed; no reconnect or replay was attempted. Close any live viewer before a new flow.")
            latchFailure(failure!!)
        }
        changed.signalAll()
        socket?.cancel()
    }

    override fun close() {
        lock.withLock {
            closed = true
            pendingActionId = null
            socket?.cancel()
            changed.signalAll()
        }
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_QUEUE_BYTES = 128 * 1024L
        private const val MAX_RECEIVED_MESSAGES = 16_384
        private val PING_ID = Regex("[A-Za-z0-9_-]{1,128}")

        fun validateUrl(url: String) {
            requireAdapter(url.toByteArray(Charsets.UTF_8).size in 1..8192 && url.none { it.isISOControl() }, "Revyl returned an invalid control connection address.")
            val uri = try { URI(url) } catch (_: Exception) { throw AdapterFailure("Revyl returned an invalid control connection address.") }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]")
            requireAdapter(uri.host != null && (uri.scheme == "wss" || (uri.scheme == "ws" && loopback)) &&
                uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535),
                "Revyl returned an invalid control connection address; secure WebSocket transport is required outside loopback.")
        }
    }
}
