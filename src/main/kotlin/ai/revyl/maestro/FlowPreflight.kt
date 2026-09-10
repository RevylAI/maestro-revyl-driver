package ai.revyl.maestro

import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.SetClipboardCommand
import maestro.orchestra.SetDarkModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.WaitForAnimationToEndCommand
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

object FlowPreflight {
    fun validate(commands: List<MaestroCommand>, platform: String): List<MaestroCommand> {
        requireAdapter(platform in setOf("android", "ios"), "Platform must be android or ios.")
        requireAdapter(commands.size in 2..1_000, "A flow must have between 1 and 999 commands.")
        requireAdapter(commands.count { it.asCommand() is ApplyConfigurationCommand } == 1, "Exactly one flow configuration is required.")
        FlowSourceOptions.validate(commands)
        var expandedCommands = 0
        var clipboardInitialized = false
        var literalClipboardText: String? = null
        return commands.map { wrapper ->
            val command = wrapper.asCommand() ?: unsupported()
            var commandCount = 1
            if (command.optional) unsupported()
            literal(command.label)
            when (command) {
                is ApplyConfigurationCommand -> {
                    commandCount = 0
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
                    if (command.retryIfNoChange == true || command.waitUntilVisible == true || command.relativePoint != null) unsupported()
                    commandCount = tapCount(command.longPress, command.repeat)
                    timeout(command.waitToSettleTimeoutMs?.toString())
                }
                is TapOnPointV2Command -> {
                    if (command.retryIfNoChange == true) unsupported()
                    commandCount = tapCount(command.longPress, command.repeat)
                    requireAdapter(Regex("\\d{1,5},\\s*\\d{1,5}|\\d{1,3}%,\\s*\\d{1,3}%").matches(command.point), "Use a literal native point or percent point.")
                    if (command.point.contains('%')) requireAdapter(command.point.replace("%", "").split(',').all { it.trim().toInt() in 0..100 }, "Percent tap coordinates must be between 0 and 100.")
                    timeout(command.waitToSettleTimeoutMs?.toString())
                }
                is PressKeyCommand -> requireSupportedKey(command.code, platform)
                is BackPressCommand -> if (platform != "android") unsupported()
                is EraseTextCommand -> {
                    commandCount = command.charactersToErase ?: DEFAULT_ERASE_CHARACTERS
                    requireAdapter(commandCount in 1..MAX_ERASE_CHARACTERS, "Erase count must be an integer from 1 to 100; the default is 50.")
                }
                is CopyTextFromCommand -> {
                    selector(command.selector)
                    clipboardInitialized = true
                    literalClipboardText = null
                }
                is SetClipboardCommand -> {
                    literal(command.text)
                    requireAdapter(command.text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES, "Clipboard text exceeds the 16 KiB limit.")
                    clipboardInitialized = true
                    literalClipboardText = command.text
                }
                is InputTextCommand -> {
                    if (platform != "android") unsupported()
                    requireFocusedText(command.text)
                }
                is PasteTextCommand -> {
                    if (platform != "android") unsupported()
                    requireAdapter(clipboardInitialized, "pasteText requires an earlier setClipboard or copyTextFrom command.")
                    literalClipboardText?.let(::requireFocusedText)
                }
                is SwipeCommand -> {
                    requireSwipeDuration(command.duration)
                    timeout(command.waitToSettleTimeoutMs?.toString())
                    if (command.relativePoint != null) unsupported()
                    command.elementSelector?.let(::selector)
                    val directional = command.direction != null && command.startPoint == null && command.endPoint == null && command.startRelative == null && command.endRelative == null
                    val absolute = command.direction == null && command.elementSelector == null && command.startPoint != null && command.endPoint != null && command.startRelative == null && command.endRelative == null
                    val relative = command.direction == null && command.elementSelector == null && command.startPoint == null && command.endPoint == null && command.startRelative != null && command.endRelative != null
                    requireAdapter(listOf(directional, absolute, relative).count { it } == 1, "Use one complete swipe coordinate or direction form.")
                    if (absolute) {
                        requireAdapter(command.startPoint != command.endPoint, "Swipe endpoints must differ.")
                        listOf(command.startPoint, command.endPoint).filterNotNull().forEach {
                            requireAdapter(it.x in 0..15_999 && it.y in 0..15_999, "Swipe coordinates must be nonnegative native points within the viewport limits.")
                        }
                    }
                    if (relative) {
                        val points = listOf(command.startRelative, command.endRelative).map {
                            requireAdapter(it != null && Regex("\\d{1,2}%,\\s*\\d{1,2}%").matches(it), "Swipe percent points must be literal integer pairs from 0 to 99 percent.")
                            it!!.replace("%", "").split(',').map(String::trim).map(String::toInt)
                        }
                        requireAdapter(points[0] != points[1], "Swipe endpoints must differ.")
                    }
                }
                is ScrollCommand -> Unit
                is SetLocationCommand -> {
                    val latitude = command.latitude.toDoubleOrNull() ?: throw AdapterFailure("Latitude must be a literal number.")
                    val longitude = command.longitude.toDoubleOrNull() ?: throw AdapterFailure("Longitude must be a literal number.")
                    requireLocation(latitude, longitude)
                }
                is SetDarkModeCommand -> Unit
                is OpenLinkCommand -> requirePlainLink(command.link, command.autoVerify != false, command.browser != false)
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
            expandedCommands += commandCount
            requireAdapter(expandedCommands <= MAX_EXPANDED_COMMANDS, "Flow exceeds the 1000 expanded-command limit.")
            if (command is TakeScreenshotCommand) MaestroCommand(command.copy(path = ".revyl-maestro/${command.path}")) else wrapper
        }
    }

    internal fun requiredCapabilities(commands: List<MaestroCommand>): Set<WorkerCapability> = commands.mapNotNull {
        when (it.asCommand()) {
            is InputTextCommand, is PasteTextCommand -> WorkerCapability.FOCUSED_TEXT_INPUT
            is SwipeCommand, is ScrollCommand -> WorkerCapability.EXPLICIT_DRAG_DURATION
            else -> null
        }
    }.toSet()

    private fun tapCount(longPress: Boolean?, repeat: maestro.TapRepeat?): Int {
        if (repeat == null) return 1
        if (longPress == true) unsupported()
        requireAdapter(repeat.repeat in 1..MAX_TAP_COUNT && repeat.delay in 0..MAX_TAP_DELAY_MS && repeat.repeat * repeat.delay <= 120_000, "Tap repeats require 1 to 100 taps, 0 to 10000 milliseconds delay, and at most 120000 milliseconds total delay.")
        return repeat.repeat
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
