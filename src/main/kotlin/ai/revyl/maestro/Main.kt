package ai.revyl.maestro

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
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
import kotlin.system.exitProcess

private const val USAGE = "Usage: revyl-maestro --session UUID --platform android|ios flow.yaml"

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
    try {
        requireAdapter(args.size == 5 && args[0] == "--session" && args[2] == "--platform", USAGE)
        requireAdapter(uuidPattern.matches(args[1]), "Session must be a canonical UUID.")
        requireAdapter(args[3] in setOf("android", "ios"), "Platform must be android or ios.")
        val flow = Path.of(args[4])
        requireAdapter(Files.isRegularFile(flow) && Files.size(flow) <= 1024 * 1024, "Flow must be a regular file no larger than 1 MiB.")
        val flowTimeout = environment["REVYL_MAESTRO_FLOW_TIMEOUT_MS"]?.toLongOrNull() ?: if (
            "REVYL_MAESTRO_FLOW_TIMEOUT_MS" in environment
        ) throw AdapterFailure("Flow timeout must be an integer from 1 to 1800000 milliseconds.") else 300_000L
        requireAdapter(flowTimeout in 1..1_800_000, "Flow timeout must be an integer from 1 to 1800000 milliseconds.")
        val settings = ConnectionSettings.fromEnvironment(environment)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flowTimeout)
        val future = executor.submit<Boolean> {
            val commands = FlowPreflight.validate(YamlCommandReader.readCommands(flow))
            FlowPreflight.prepareScreenshotDirectory(commands)
            RevylClient(settings, deadline).use { connection ->
                client.set(connection)
                connection.attach(args[1], args[3])
                val driver = RevylDriver(connection, args[3])
                try {
                    driver.open()
                    val result = runBlocking {
                        withTimeout(flowTimeout) {
                            Orchestra(
                                Maestro(driver),
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
            throw AdapterFailure("Flow execution timed out. An action may have executed; nothing was retried.")
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
        client.get()?.close()
        executor.shutdownNow()
    }
}
