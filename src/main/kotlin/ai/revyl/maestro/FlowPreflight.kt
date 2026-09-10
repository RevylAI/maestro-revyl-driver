package ai.revyl.maestro

import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

object FlowPreflight {
    fun validate(commands: List<MaestroCommand>): List<MaestroCommand> {
        requireAdapter(commands.size in 2..1_000, "A flow must have between 1 and 999 commands.")
        requireAdapter(commands.count { it.asCommand() is ApplyConfigurationCommand } == 1, "Exactly one flow configuration is required.")
        return commands.map { wrapper ->
            val command = wrapper.asCommand() ?: unsupported()
            if (command.optional) unsupported()
            literal(command.label)
            when (command) {
                is ApplyConfigurationCommand -> {
                    val config = command.config
                    requireAdapter(config.appId?.matches(appIdPattern) == true, "Use a native appId, not a URL or expression.")
                    if (config.ext.isNotEmpty() || config.properties.isNotEmpty() || config.onFlowStart != null || config.onFlowComplete != null) unsupported()
                    literal(config.name)
                    config.tags?.forEach { literal(it) }
                }
                is AssertConditionCommand -> {
                    val condition = command.condition
                    if (condition.platform != null || condition.scriptCondition != null || (condition.visible == null) == (condition.notVisible == null)) unsupported()
                    literal(condition.label)
                    condition.visible?.let(::selector)
                    condition.notVisible?.let(::selector)
                    timeout(command.timeout)
                }
                is TapOnElementCommand -> {
                    selector(command.selector)
                    if (command.retryIfNoChange == true || command.waitUntilVisible == true || command.longPress == true || command.repeat != null || command.relativePoint != null) unsupported()
                    timeout(command.waitToSettleTimeoutMs?.toString())
                }
                is TapOnPointV2Command -> {
                    if (command.retryIfNoChange == true || command.longPress == true || command.repeat != null) unsupported()
                    requireAdapter(Regex("\\d{1,5},\\s*\\d{1,5}|\\d{1,3}%,\\s*\\d{1,3}%").matches(command.point), "Use a literal native point or percent point.")
                    if (command.point.contains('%')) requireAdapter(command.point.replace("%", "").split(',').all { it.trim().toInt() in 0..100 }, "Percent tap coordinates must be between 0 and 100.")
                    timeout(command.waitToSettleTimeoutMs?.toString())
                }
                is LaunchAppCommand -> {
                    requireAdapter(command.appId.matches(appIdPattern), "Launch requires a native app ID.")
                    if (command.stopApp != false || command.permissions == null || command.permissions?.isNotEmpty() == true ||
                        command.clearState != null || command.clearKeychain != null || command.launchArguments != null) unsupported()
                }
                is TakeScreenshotCommand -> {
                    if (command.cropOn != null) unsupported()
                    requireAdapter(Regex("[A-Za-z0-9_-]{1,80}").matches(command.path), "Screenshot names must be simple names without directories or extensions.")
                }
                is WaitForAnimationToEndCommand -> timeout(command.timeout)
                else -> unsupported()
            }
            if (command is TakeScreenshotCommand) MaestroCommand(command.copy(path = ".revyl-maestro/${command.path}")) else wrapper
        }
    }

    fun prepareScreenshotDirectory(commands: List<MaestroCommand>) {
        val screenshots = commands.mapNotNull { it.takeScreenshotCommand }
        if (screenshots.isEmpty()) return
        val directory = Path.of(".revyl-maestro")
        requireAdapter(!Files.isSymbolicLink(directory), "Screenshot directory must not be a symlink.")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        }
        requireAdapter(Files.isDirectory(directory, NOFOLLOW_LINKS), "Screenshot output must be a private directory.")
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
        screenshots.forEach {
            val path = Path.of("${it.path}.png")
            requireAdapter(!Files.exists(path, NOFOLLOW_LINKS), "Screenshot output already exists; choose a new name or remove the private artifact.")
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
    }

    private fun timeout(value: String?) {
        if (value != null) requireAdapter(value.toLongOrNull() in 1L..120_000L, "Command timeout must be a literal integer from 1 to 120000 milliseconds.")
    }

    private fun literal(value: String?) {
        if (value?.contains("\${") == true) unsupported()
    }

    private fun selector(value: ElementSelector) {
        if (value.optional || value.css != null) unsupported()
        literal(value.textRegex)
        literal(value.idRegex)
        literal(value.index)
        value.index?.let { requireAdapter(it.toIntOrNull() in 0..10_000, "Selector index must be a literal non-negative integer.") }
        listOf(value.below, value.above, value.leftOf, value.rightOf, value.containsChild, value.childOf).filterNotNull().forEach(::selector)
        value.containsDescendants?.forEach(::selector)
    }
}
