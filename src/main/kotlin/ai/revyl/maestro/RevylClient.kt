package ai.revyl.maestro

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AdapterFailure(message: String) : RuntimeException(message)

internal val json = jacksonObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
internal val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal val appIdPattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

internal fun requireAdapter(condition: Boolean, message: String) {
    if (!condition) throw AdapterFailure(message)
}

internal fun unsupported(): Nothing = throw AdapterFailure("Unsupported Maestro operation or option; no adapter retry was attempted.")

private fun workerUnsupported(): Nothing = throw AdapterFailure("Revyl worker cannot perform this operation exactly on the current page or element. No adapter retry was attempted.")

private fun isWorkerUnsupported(error: JsonNode?): Boolean = when (error?.textValue() ?: error?.path("code")?.textValue()) {
    "not_available_on_platform", "unsupported_web_element" -> true
    else -> false
}

private fun requireWorkerOperationSupported(result: JsonNode) {
    if (isWorkerUnsupported(result.path("error"))) workerUnsupported()
}

data class ConnectionSettings(val origin: String, val apiKey: String, val timeoutMs: Long) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): ConnectionSettings {
            val key = environment["REVYL_API_KEY"]?.trim().orEmpty()
            requireAdapter(key.isNotEmpty() && key.all { it.code in 33..126 }, "Set a valid REVYL_API_KEY in the CLI process environment.")
            val origin = environment["REVYL_MAESTRO_API_URL"] ?: "https://backend.revyl.ai"
            val uri = try { URI(origin) } catch (_: Exception) {
                throw AdapterFailure("REVYL_MAESTRO_API_URL must be an HTTPS origin.")
            }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]")
            requireAdapter(
                uri.host != null && (uri.scheme == "https" || (uri.scheme == "http" && loopback)) &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath in setOf("", "/") && (uri.port == -1 || uri.port in 1..65535),
                "REVYL_MAESTRO_API_URL must be an HTTPS origin; HTTP is allowed only on loopback.",
            )
            val timeout = environment["REVYL_MAESTRO_REQUEST_TIMEOUT_MS"]?.toLongOrNull() ?: if (
                "REVYL_MAESTRO_REQUEST_TIMEOUT_MS" in environment
            ) throw AdapterFailure("Request timeout must be an integer from 1 to 120000 milliseconds.") else 30_000L
            requireAdapter(timeout in 1..120_000, "Request timeout must be an integer from 1 to 120000 milliseconds.")
            return ConnectionSettings(origin.removeSuffix("/"), key, timeout)
        }
    }

    override fun toString(): String = "ConnectionSettings(redacted)"
}

class RevylClient(private val settings: ConnectionSettings, private val deadlineNanos: Long) : Closeable {
    private val closed = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<AdapterFailure?>()
    private val correlationId = UUID.randomUUID().toString()
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!response.isSuccessful) {
                val status = response.code
                val errorCode = runCatching { json.readTree(response.peekBody(8_192).bytes()).path("error") }.getOrNull()
                response.close()
                if (status == 501 || isWorkerUnsupported(errorCode)) workerUnsupported()
                throw AdapterFailure(when (status) {
                    401, 403 -> "Revyl denied access. Check the process API key and session permissions."
                    404, 410 -> "The Revyl session is unavailable or inaccessible."
                    else -> "Revyl request failed (HTTP $status); no adapter retry was attempted."
                })
            }
            response
        }
        .build()
    private var workflowRunId: String? = null
    private var attachedPlatform: String? = null
    private var verifiedCapabilities: Set<WorkerCapability> = emptySet()
    private var viewerInput: ViewerInputChannel? = null

    fun attach(sessionId: String, platform: String) = guard {
        requireAdapter(workflowRunId == null, "The local client is already attached.")
        requireAdapter(uuidPattern.matches(sessionId), "Session must be a canonical UUID.")
        requireAdapter(platform in setOf("android", "ios", "web"), "Platform must be android, ios, or web.")
        val detail = readJson(request("device-sessions/$sessionId"))
        val workflow = detail.path("workflow_run_id").textValue()
        requireAdapter(
            detail.path("id").textValue()?.equals(sessionId, ignoreCase = true) == true &&
                detail.path("status").textValue() == "running" &&
                detail.path("platform").textValue()?.lowercase() == platform &&
                workflow != null && uuidPattern.matches(workflow),
            "The authorized Revyl session must be running, match the platform, and have a valid workflow UUID.",
        )
        workflowRunId = workflow
        attachedPlatform = platform
        verifiedCapabilities = emptySet()
    }

    internal fun establishViewerInput() = guard {
        requireAdapter(viewerInput == null, "The viewer input connection has already been established.")
        val workflow = attachedWorkflow()
        val connection = readJson(request("streaming/worker-connection/$workflow"))
        requireAdapter(connection.isObject && connection.path("status").textValue() == "ready" &&
            connection.path("workflow_run_id").textValue()?.equals(workflow, ignoreCase = true) == true,
            "Revyl did not authorize a ready control connection for the attached workflow. No flow mutation was sent.")
        val url = connection.path("worker_ws_url").textValue()
            ?: throw AdapterFailure("Revyl did not provide a control connection. No flow mutation was sent.")
        ViewerInputChannel.validateUrl(url)
        val channel = ViewerInputChannel(settings.timeoutMs, deadlineNanos) { terminalFailure.compareAndSet(null, it) }
        viewerInput = channel
        channel.connect(url)
    }

    internal fun inputText(text: String, platform: String) = guard {
        requireFocusedText(text)
        requireAdapter(attachedPlatform == platform && workflowRunId != null && viewerInput != null,
            "Text input requires an attached session and an established control connection; no input was sent.")
        viewerInput!!.inputText(text, platform == "ios")
    }

    internal fun verifyCapabilities(required: Set<WorkerCapability>, optional: Set<WorkerCapability> = emptySet()) = guard {
        val requiredCapabilities = if (attachedPlatform == "web") required + WorkerCapability.MAESTRO_WEB else required
        if (requiredCapabilities.isEmpty() && optional.isEmpty()) return@guard
        val health = readJson(request("device-proxy/${attachedWorkflow()}/health"))
        if (attachedPlatform == "web") {
            requireAdapter(health.isObject && listOf("ready", "current_fence", "first_frame_observed").all {
                health.path(it).isBoolean && health.path(it).booleanValue()
            }, "Worker health must confirm the ready, current-fenced web session and its first frame.")
        } else {
            requireAdapter(health.isObject && health.path("status").textValue() == "ok" &&
                health.path("workflow_run_id").textValue()?.equals(attachedWorkflow(), ignoreCase = true) == true &&
                health.path("platform").textValue() == attachedPlatform &&
                health.path("device_connected").isBoolean && health.path("device_connected").booleanValue(),
                "Worker health must confirm the attached running device and platform.")
        }
        requiredCapabilities.forEach { capability ->
            requireAdapter(health.path(capability.field).isBoolean && health.path(capability.field).booleanValue(), "The attached worker does not advertise a required command capability. No flow mutation was sent.")
        }
        verifiedCapabilities = requiredCapabilities + optional.filter { health.path(it.field).isBoolean && health.path(it.field).booleanValue() }
    }

    fun read(action: String): ByteArray {
        requireAdapter(action in setOf("hierarchy", "screenshot"), "Unsupported proxy read.")
        return request("device-proxy/${attachedWorkflow()}/$action")
    }

    internal fun webRead(action: String, body: Map<String, Any>? = null): JsonNode = guard {
        requireAdapter(attachedPlatform == "web" && WorkerCapability.MAESTRO_WEB in verifiedCapabilities,
            "Required web worker capability has not been verified; no state was read.")
        requireAdapter(when (action) {
            "maestro_web_info", "maestro_web_hierarchy" -> body == null
            "maestro_web_query" -> body != null
            else -> false
        }, "Unsupported web proxy read.")
        val result = readJson(request("device-proxy/${attachedWorkflow()}/$action", body))
        requireWorkerOperationSupported(result)
        requireAdapter(result.isObject, "Revyl returned an invalid web state.")
        result
    }

    internal fun webAct(action: String, body: Map<String, Any>) = guard {
        requireAdapter(attachedPlatform == "web" && WorkerCapability.MAESTRO_WEB in verifiedCapabilities,
            "Required web worker capability has not been verified; no mutation was sent.")
        requireAdapter(action in setOf("navigate", "back", "key", "maestro_web_scroll", "maestro_web_clear_state", "drag", "longpress",
            "maestro_web_tap", "maestro_web_input", "maestro_web_launch"), "Unsupported web proxy mutation.")
        val result = readJson(request("device-proxy/${attachedWorkflow()}/$action", body))
        requireWorkerOperationSupported(result)
        val responseAction = if (action == "longpress") "long_press" else action
        requireAdapter(result.isObject &&
            result.path("success").isBoolean && result.path("success").booleanValue() &&
            result.path("action").textValue() == responseAction &&
            result.path("latency_ms").isNumber && result.path("latency_ms").doubleValue().isFinite() && result.path("latency_ms").doubleValue() >= 0 &&
            (!result.has("error") || result.path("error").isNull) &&
            result.path("execution_uncertain").isBoolean && !result.path("execution_uncertain").booleanValue(),
            "Revyl did not confirm web action success. It may have executed; no adapter retry was attempted.")
    }

    internal fun readState(action: String): JsonNode = guard {
        val capability = when (action) {
            "keyboard" -> WorkerCapability.KEYBOARD_CONTROL
            "appearance" -> WorkerCapability.APPEARANCE_READ
            "airplane_mode" -> WorkerCapability.AIRPLANE_MODE
            "webview_hierarchy" -> WorkerCapability.WEBVIEW_HIERARCHY
            else -> throw AdapterFailure("Unsupported proxy read.")
        }
        requireAdapter(capability in verifiedCapabilities, "Required worker capability has not been verified; no state was read.")
        val result = readJson(request("device-proxy/${attachedWorkflow()}/$action"))
        requireAdapter(result.isObject, "Revyl returned an invalid device state.")
        result
    }

    internal fun requireCapability(capability: WorkerCapability) = guard {
        requireAdapter(capability in verifiedCapabilities, "Required worker capability has not been verified; no mutation was sent.")
    }

    internal fun stopScreenRecording(recordingId: String): ByteArray = guard {
        requireAdapter(WorkerCapability.SCREEN_RECORDING in verifiedCapabilities, "Required worker capability has not been verified; no mutation was sent.")
        val (contentType, bytes) = exchange("device-proxy/${attachedWorkflow()}/stop_screen_recording", mapOf("recording_id" to recordingId), MAX_RECORDING_BYTES)
        requireAdapter(contentType?.startsWith("video/mp4") == true && bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp",
            "Revyl did not return a screen recording. It may have stopped; no adapter retry was attempted.")
        bytes
    }

    internal fun startScreenRecording(): String = guard {
        val recordingId = actForResult("start_screen_recording", emptyMap()).path("recording_id").textValue()
        requireAdapter(recordingId != null && recordingId.length in 1..128 && recordingId.all { it.code in 33..126 },
            "Revyl did not return a valid recording identifier. Recording may have started; no adapter retry was attempted.")
        recordingId!!
    }

    fun act(action: String, body: Map<String, Any>) {
        actForResult(action, body)
    }

    private fun actForResult(action: String, body: Map<String, Any>): JsonNode = guard {
        requireAdapter(action in setOf("tap", "launch", "key", "go_home", "back", "longpress", "set_location", "set_appearance", "open_url", "drag",
            "kill_app", "clear_app_state", "clear_keychain", "set_permissions", "set_orientation",
            "hide_keyboard", "add_media", "set_airplane_mode", "start_screen_recording"), "Unsupported proxy mutation.")
        val capability = when (action) {
            "drag" -> WorkerCapability.EXPLICIT_DRAG_DURATION
            "kill_app" -> if (body["mode"] == "process_death") WorkerCapability.PROCESS_DEATH_KILL else WorkerCapability.APP_LIFECYCLE
            "clear_app_state", "clear_keychain", "set_permissions" -> WorkerCapability.APP_LIFECYCLE
            "launch" -> WorkerCapability.APP_LIFECYCLE.takeIf { "arguments" in body }
            "set_orientation" -> WorkerCapability.PRECISE_ORIENTATION
            "hide_keyboard" -> WorkerCapability.KEYBOARD_CONTROL
            "add_media" -> WorkerCapability.ADD_MEDIA
            "set_airplane_mode" -> WorkerCapability.AIRPLANE_MODE
            "start_screen_recording" -> WorkerCapability.SCREEN_RECORDING
            else -> null
        }
        requireAdapter(capability == null || capability in verifiedCapabilities, "Required worker capability has not been verified; no mutation was sent.")
        if (action == "set_permissions" && attachedPlatform == "ios") {
            val permissions = body["permissions"] as? Map<*, *> ?: throw AdapterFailure("Permissions require a map.")
            requireAdapter(permissions.keys.none { it in extendedIosPermissions } || WorkerCapability.EXTENDED_IOS_PERMISSIONS in verifiedCapabilities,
                "Required iOS permission capability has not been verified; no mutation was sent.")
        }
        val result = readJson(request("device-proxy/${attachedWorkflow()}/$action", body))
        val responseAction = when (action) {
            "longpress" -> "long_press"
            else -> action
        }
        requireAdapter(
            result.path("success").isBoolean && result.path("success").booleanValue() &&
                result.path("action").textValue() == responseAction &&
                (!result.has("error") || result.path("error").isNull),
            "Revyl did not confirm action success. It may have executed; no adapter retry was attempted.",
        )
        result
    }

    private fun attachedWorkflow(): String = workflowRunId ?: throw AdapterFailure("No Revyl session is attached.")

    fun verifyHealthy() {
        terminalFailure.get()?.let { throw it }
        requireAdapter(!closed.get(), "The local Revyl client is closed.")
    }

    fun <T> guard(action: () -> T): T {
        verifyHealthy()
        try { return action() } catch (failure: AdapterFailure) {
            terminalFailure.compareAndSet(null, failure)
            throw failure
        }
    }

    private fun request(path: String, body: Map<String, Any>? = null): ByteArray = exchange(path, body, MAX_RESPONSE_BYTES.toLong()).second

    private fun exchange(path: String, body: Map<String, Any>?, maxResponseBytes: Long): Pair<String?, ByteArray> = guard {
        requireAdapter(!closed.get() && !Thread.currentThread().isInterrupted, "The local Revyl client is closed or cancelled.")
        val remainingNanos = deadlineNanos - System.nanoTime()
        requireAdapter(remainingNanos > 0, "Flow execution timed out. An action may have executed; no adapter retry was attempted.")
        val builder = Request.Builder().url("${settings.origin}/api/v1/execution/$path")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("User-Agent", "revyl-maestro/0.1.0")
            .header("X-Revyl-Agent", "Maestro")
            .header("X-Revyl-Agent-Session-Id", correlationId)
        if (body != null) builder.post(json.writeValueAsBytes(body).toRequestBody("application/json".toMediaType()))
        val call = http.newCall(builder.build())
        call.timeout().timeout(minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(settings.timeoutMs)), TimeUnit.NANOSECONDS)
        try {
            call.execute().use { response ->
                val source = response.body?.source() ?: throw AdapterFailure("Revyl returned an empty response.")
                requireAdapter(!source.request(maxResponseBytes + 1L), if (maxResponseBytes == MAX_RESPONSE_BYTES.toLong()) "Revyl response exceeded the 16 MiB limit." else "Revyl response exceeded its size limit.")
                response.header("Content-Type") to source.readByteArray()
            }
        } catch (_: IOException) {
            throw AdapterFailure("Revyl transport failed, timed out, or was cancelled. An action may have executed; no adapter retry was attempted.")
        }
    }

    override fun close() {
        closed.set(true)
        viewerInput?.close()
        workflowRunId = null
        attachedPlatform = null
        verifiedCapabilities = emptySet()
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        fun readJson(bytes: ByteArray): JsonNode = try {
            json.readTree(bytes) ?: throw AdapterFailure("Revyl returned invalid JSON.")
        } catch (_: Exception) {
            throw AdapterFailure("Revyl returned invalid JSON.")
        }
    }
}
