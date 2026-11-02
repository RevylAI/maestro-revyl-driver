package ai.revyl.maestro

import maestro.Driver
import maestro.MaestroException
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.SetClipboardCommand
import okio.Buffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal const val FAILURE_DIRECTORY = "failures"

internal data class FailedCommand(val failure: Throwable, val commandPath: List<String>)

internal fun FailedCommand?.withFailure(command: MaestroCommand, failure: Throwable): FailedCommand =
    if (this?.failure === failure) copy(commandPath = listOf(failureDescription(command)) + commandPath)
    else FailedCommand(failure, listOf(failureDescription(command)))

internal fun failureDescription(command: MaestroCommand): String = when (command.asCommand()) {
    is InputTextCommand -> "Input text (value omitted)"
    is SetClipboardCommand -> "Set clipboard (value omitted)"
    is EvalScriptCommand -> "Evaluate script (source omitted)"
    else -> command.description()
}

internal fun newFailureArtifactDirectory(): Path {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    return Path.of(PRIVATE_OUTPUT_DIRECTORY, FAILURE_DIRECTORY, "$stamp-${UUID.randomUUID().toString().take(8)}")
}

internal fun saveFailureArtifacts(failure: MaestroException, driver: Driver, directory: Path): List<String> {
    try {
        listOf(directory.parent.parent, directory.parent, directory).forEach(FlowPreflight::privateDirectory)
    } catch (_: Exception) {
        return listOf("directory not created")
    }
    fun write(name: String, capture: () -> ByteArray): String = try {
        val bytes = capture()
        val file = Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(file, bytes)
        name
    } catch (_: Exception) {
        "$name not captured"
    }
    val hierarchy = write("hierarchy.json") {
        val tree = (failure as? MaestroException.AssertionFailure)?.hierarchyRoot ?: driver.contentDescriptor(false)
        json.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree)
    }
    val screenshot = write("screenshot.png") { Buffer().also { driver.takeScreenshot(it, false) }.readByteArray() }
    return listOf(hierarchy, screenshot)
}
