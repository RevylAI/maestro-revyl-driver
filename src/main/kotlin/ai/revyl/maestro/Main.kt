package ai.revyl.maestro

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import maestro.Maestro
import maestro.MaestroException
import maestro.js.GraalJsEngine
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val USAGE = "Usage: revyl-maestro [--maestro-compat [-e NAME=VALUE]...] --session UUID --platform android|ios|web flow.yaml\n       revyl-maestro [--maestro-compat [-e NAME=VALUE]...] --validate-only --platform android|ios|web flow.yaml"

private val maestroInternalEnvironment = setOf("MAESTRO_SHARD_ID", "MAESTRO_SHARD_INDEX")

private class Invocation(val compat: Boolean, val validateOnly: Boolean, val session: String?, val platform: String, val flow: Path, val parameters: Map<String, String>)

private fun parseInvocation(args: Array<String>): Invocation {
    var compat = false
    var validateOnly = false
    var session: String? = null
    var platform: String? = null
    val parameters = linkedMapOf<String, String>()
    val positional = mutableListOf<String>()
    var index = 0
    fun value(): String = args.getOrNull(++index) ?: throw AdapterFailure(USAGE)
    while (index < args.size) {
        when (val argument = args[index]) {
            "--maestro-compat" -> { requireAdapter(!compat, USAGE); compat = true }
            "--validate-only" -> { requireAdapter(!validateOnly, USAGE); validateOnly = true }
            "--session" -> { requireAdapter(session == null, USAGE); session = value() }
            "--platform" -> { requireAdapter(platform == null, USAGE); platform = value() }
            "-e", "--env" -> {
                val definition = value()
                val name = definition.substringBefore('=', "")
                requireAdapter(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) && definition.contains('=') && parameters.put(name, definition.substringAfter('=')) == null,
                    "Use -e NAME=VALUE with a unique identifier name.")
            }
            else -> { requireAdapter(!argument.startsWith("-"), USAGE); positional += argument }
        }
        index++
    }
    requireAdapter(positional.size == 1 && platform in setOf("android", "ios", "web") && (session == null) == validateOnly, USAGE)
    requireAdapter(compat || parameters.isEmpty(), "Flow parameters from the command line require --maestro-compat.")
    if (session != null) requireAdapter(uuidPattern.matches(session), "Session must be a canonical UUID.")
    return Invocation(compat, validateOnly, session, platform!!, Path.of(positional.single()), parameters)
}

internal fun compatEnvironment(parameters: Map<String, String>, environment: Map<String, String>, flow: Path): Map<String, String> {
    val inherited = environment.filterKeys { it.startsWith("MAESTRO_") && it !in parameters && it !in maestroInternalEnvironment }.filterValues { it.isNotEmpty() }
    val result = (parameters - maestroInternalEnvironment) + inherited +
        mapOf("MAESTRO_FILENAME" to flow.fileName.toString().substringBeforeLast('.'), "MAESTRO_SHARD_ID" to "1", "MAESTRO_SHARD_INDEX" to "0")
    val apiKey = environment["REVYL_API_KEY"]?.trim().orEmpty()
    requireAdapter(result.keys.none { it.startsWith("REVYL_") } && (apiKey.isEmpty() || result.values.none { it.contains(apiKey) }),
        "Maestro flow parameters must not carry Revyl credentials or REVYL_ variables.")
    return result
}

private fun readFlow(invocation: Invocation, environment: Map<String, String>): ValidatedFlow {
    val flow = invocation.flow
    requireAdapter(Files.isRegularFile(flow) && Files.size(flow) <= 1024 * 1024, "Flow must be a regular file no larger than 1 MiB.")
    if (!invocation.compat) FlowSourceOptions.validate(flow)
    val parsed = YamlCommandReader.readCommands(flow)
    val commands = if (invocation.compat) parsed.withEnv(compatEnvironment(invocation.parameters, environment, flow)) else parsed
    return FlowPreflight.validateFlow(commands, invocation.platform, invocation.compat, flow)
}

fun main(args: Array<String>) {
    exitProcess(runCli(args, System.getenv(), System.out, System.err))
}

fun runCli(args: Array<String>, environment: Map<String, String>, output: PrintStream, errors: PrintStream): Int {
    Configurator.setRootLevel(Level.OFF)
    java.util.logging.LogManager.getLogManager().reset()
    System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
    if (args.contentEquals(arrayOf("--help"))) { output.println(USAGE); return 0 }
    val client = AtomicReference<RevylClient?>()
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "revyl-maestro-flow").apply { isDaemon = true } }
    var scriptHttp: OkHttpClient? = null
    val scriptsClosed = AtomicBoolean(false)
    try {
        val invocation = parseInvocation(args)
        val platform = invocation.platform
        if (invocation.validateOnly) {
            val validated = try { readFlow(invocation, environment) } catch (failure: AdapterFailure) {
                throw AdapterFailure("${failure.message} Rejected command: ${FlowPreflight.rejectedCommand ?: "flow source"}.")
            }
            val capabilities = FlowPreflight.requiredCapabilities(validated.commands, platform).map { it.field }.sorted()
            val subset = if (invocation.compat) "the Maestro compatibility mode" else "the supported subset"
            output.println("Flow is within $subset. Required worker capabilities: ${capabilities.ifEmpty { listOf("none") }.joinToString()}")
            return 0
        }
        val flowTimeout = environment["REVYL_MAESTRO_FLOW_TIMEOUT_MS"]?.toLongOrNull() ?: if (
            "REVYL_MAESTRO_FLOW_TIMEOUT_MS" in environment
        ) throw AdapterFailure("Flow timeout must be an integer from 1 to 1800000 milliseconds.") else 300_000L
        requireAdapter(flowTimeout in 1..1_800_000, "Flow timeout must be an integer from 1 to 1800000 milliseconds.")
        val settings = ConnectionSettings.fromEnvironment(environment)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flowTimeout)
        if (invocation.compat) scriptHttp = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .callTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(settings.timeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                requireAdapter(!scriptsClosed.get() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline, "Flow execution timed out or was cancelled.")
                chain.call().timeout().timeout(minOf(settings.timeoutMs, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)), TimeUnit.MILLISECONDS)
                chain.proceed(chain.request())
            }
            .build()
        val future = executor.submit<Boolean> {
            val validated = readFlow(invocation, environment)
            val commands = validated.commands
            val requiredCapabilities = FlowPreflight.requiredCapabilities(commands, platform)
            val optionalCapabilities = FlowPreflight.optionalCapabilities(commands, platform)
            FlowPreflight.prepareOutput(validated, invocation.compat)
            RevylClient(settings, deadline).use { connection ->
                client.set(connection)
                connection.attach(invocation.session!!, platform)
                connection.verifyCapabilities(requiredCapabilities, optionalCapabilities)
                if (platform != "web" && FlowPreflight.requiresViewerInput(commands)) connection.establishViewerInput()
                val driver = RevylDriver(connection, platform)
                try {
                    driver.open()
                    val result = runBlocking {
                        withTimeout(flowTimeout) {
                            Orchestra(
                                Maestro(driver),
                                httpClient = scriptHttp,
                                jsEngineFactory = { if (invocation.compat) CompatJsEngine(GraalJsEngine(scriptHttp ?: OkHttpClient(), platform), settings.apiKey, validated.dynamicPaths) else GraalJsEngine(platform = platform) },
                                onCommandStart = { _, _ -> connection.verifyHealthy() },
                                onCommandComplete = { _, _ -> connection.verifyHealthy() },
                                onCommandFailed = { _, _, failure -> throw failure },
                            ).runFlow(commands)
                        }
                    }
                    connection.verifyHealthy()
                    result.success
                } finally { driver.close() }
            }
        }
        val success = try { future.get(flowTimeout, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) {
            client.get()?.close()
            future.cancel(true)
            throw AdapterFailure("Flow execution timed out. An action may have executed; no adapter retry was attempted.")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
        requireAdapter(success, "Maestro reported a failed flow.")
        output.println("Maestro flow passed. Detached locally; the Revyl session remains running.")
        return 0
    } catch (failure: Throwable) {
        val message = when (failure) {
            is AdapterFailure -> failure.message
            is MaestroException -> "Maestro assertion or command failed. Customer details are suppressed."
            else -> "Maestro flow parsing or execution failed. Customer details are suppressed."
        }
        errors.println(message)
        return 1
    } finally {
        scriptsClosed.set(true)
        client.get()?.close()
        scriptHttp?.let { it.dispatcher.cancelAll(); it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
        executor.shutdownNow()
    }
}
