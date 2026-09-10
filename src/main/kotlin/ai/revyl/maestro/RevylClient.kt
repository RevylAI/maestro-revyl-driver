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
                response.close()
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

    fun attach(sessionId: String, platform: String) {
        requireAdapter(uuidPattern.matches(sessionId), "Session must be a canonical UUID.")
        requireAdapter(platform in setOf("android", "ios"), "Platform must be android or ios.")
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

    internal fun verifyCapabilities(required: Set<WorkerCapability>) = guard {
        if (required.isEmpty()) return@guard
        val health = readJson(request("device-proxy/${attachedWorkflow()}/health"))
        requireAdapter(health.isObject && health.path("status").textValue() == "ok" &&
            health.path("workflow_run_id").textValue()?.equals(attachedWorkflow(), ignoreCase = true) == true &&
            health.path("platform").textValue() == attachedPlatform &&
            health.path("device_connected").isBoolean && health.path("device_connected").booleanValue(),
            "Worker health must confirm the attached running device and platform.")
        required.forEach { capability ->
            requireAdapter(health.path(capability.field).isBoolean && health.path(capability.field).booleanValue(), "The attached worker does not advertise a required command capability. No flow mutation was sent.")
        }
        verifiedCapabilities = required
    }

    fun read(action: String): ByteArray {
        requireAdapter(action in setOf("hierarchy", "screenshot"), "Unsupported proxy read.")
        return request("device-proxy/${attachedWorkflow()}/$action")
    }

    fun act(action: String, body: Map<String, Any>) = guard {
        requireAdapter(action in setOf("tap", "launch", "key", "go_home", "back", "longpress", "set_location", "set_appearance", "open_url", "text-input", "drag"), "Unsupported proxy mutation.")
        val capability = when (action) {
            "text-input" -> WorkerCapability.FOCUSED_TEXT_INPUT
            "drag" -> WorkerCapability.EXPLICIT_DRAG_DURATION
            else -> null
        }
        requireAdapter(capability == null || capability in verifiedCapabilities, "Required worker capability has not been verified; no mutation was sent.")
        val result = readJson(request("device-proxy/${attachedWorkflow()}/$action", body))
        val responseAction = when (action) {
            "longpress" -> "long_press"
            "text-input" -> "input"
            else -> action
        }
        requireAdapter(
            result.path("success").isBoolean && result.path("success").booleanValue() &&
                result.path("action").textValue() == responseAction &&
                (!result.has("error") || result.path("error").isNull),
            "Revyl did not confirm action success. It may have executed; no adapter retry was attempted.",
        )
    }

    private fun attachedWorkflow(): String = workflowRunId ?: throw AdapterFailure("No Revyl session is attached.")

    fun verifyHealthy() { terminalFailure.get()?.let { throw it } }

    fun <T> guard(action: () -> T): T {
        verifyHealthy()
        try { return action() } catch (failure: AdapterFailure) {
            terminalFailure.compareAndSet(null, failure)
            throw failure
        }
    }

    private fun request(path: String, body: Map<String, Any>? = null): ByteArray = guard {
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
                requireAdapter(!source.request(MAX_RESPONSE_BYTES + 1L), "Revyl response exceeded the 16 MiB limit.")
                source.readByteArray()
            }
        } catch (_: IOException) {
            throw AdapterFailure("Revyl transport failed, timed out, or was cancelled. An action may have executed; no adapter retry was attempted.")
        }
    }

    override fun close() {
        closed.set(true)
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
