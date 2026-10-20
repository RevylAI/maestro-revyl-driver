package ai.revyl.maestro

import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
import maestro.orchestra.StopAppCommand
import maestro.device.DeviceOrientation
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RunFlowCommand
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
    @Volatile var rejectedCommand: String? = null
        private set

    fun validate(commands: List<MaestroCommand>, platform: String): List<MaestroCommand> {
        rejectedCommand = null
        requireAdapter(platform in setOf("android", "ios"), "Platform must be android or ios.")
        FlowSourceOptions.validate(commands)
        val resolvedCommands = FlowParameters.resolve(commands, platform)
        var expandedCommands = 0
        var sourceCommands = 0
        var clipboardInitialized = false
        var clipboardSafeToPaste = true
        fun validateCommands(commands: List<MaestroCommand>, depth: Int, hasConfig: Boolean): List<MaestroCommand> {
            requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
            requireAdapter(commands.size > if (hasConfig) 1 else 0, "A flow must contain commands.")
            requireAdapter(commands.count { it.asCommand() is ApplyConfigurationCommand } == if (hasConfig) 1 else 0, "Unexpected flow configuration.")
            return commands.map { wrapper ->
                val command = wrapper.asCommand() ?: unsupported()
                try {
                    if (command !is ApplyConfigurationCommand) {
                        sourceCommands++
                        requireAdapter(sourceCommands <= MAX_SOURCE_COMMANDS, "Flow exceeds the 999 source-command limit.")
                    }
                    var commandCount = 1
                    var validated = wrapper
                    if (command.optional) unsupported()
                    literal(command.label)
                    when (command) {
                        is ApplyConfigurationCommand -> {
                            commandCount = 0
                            validateConfig(command.config)
                        }
                        is RunFlowCommand -> {
                            command.condition?.let { condition ->
                                if (condition.scriptCondition != null) unsupported()
                                requireAdapter(condition.platform?.toString()?.lowercase() in setOf(null, "android", "ios"), "Condition platform must be android or ios.")
                                requireAdapter(condition.platform != null || condition.visible != null || condition.notVisible != null, "runFlow requires a nonempty condition.")
                                literal(condition.label)
                                condition.visible?.let(::selector)
                                condition.notVisible?.let(::selector)
                            }
                            literal(command.sourceDescription)
                            command.config?.let(::validateConfig)
                            val clipboardInitializedBefore = clipboardInitialized
                            val clipboardSafeBefore = clipboardSafeToPaste
                            validated = wrapper.copy(runFlowCommand = command.copy(commands = validateCommands(command.commands, depth + 1, command.sourceDescription != null)))
                            if (command.condition != null) {
                                clipboardInitialized = clipboardInitializedBefore && clipboardInitialized
                                clipboardSafeToPaste = clipboardSafeBefore && clipboardSafeToPaste
                            }
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
                            clipboardSafeToPaste = true
                        }
                        is SetClipboardCommand -> {
                            literal(command.text)
                            requireAdapter(command.text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES, "Clipboard text exceeds the 16 KiB limit.")
                            clipboardInitialized = true
                            clipboardSafeToPaste = try { requireFocusedText(command.text); true } catch (_: AdapterFailure) { false }
                        }
                        is InputTextCommand -> {
                            requireFocusedText(command.text)
                        }
                        is PasteTextCommand -> {
                            requireAdapter(clipboardInitialized, "pasteText requires an earlier setClipboard or copyTextFrom command.")
                            requireAdapter(clipboardSafeToPaste, "pasteText requires valid text on every possible preceding clipboard path.")
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
                            command.permissions?.let { requirePermissions(it, platform) }
                            command.launchArguments?.let(::requireLaunchArguments)
                        }
                        is StopAppCommand -> requireAppId(command.appId)
                        is KillAppCommand -> {
                            requireAppId(command.appId)
                            if (platform == "android") unsupported()
                        }
                        is ClearStateCommand -> requireAppId(command.appId)
                        is ClearKeychainCommand -> Unit
                        is SetPermissionsCommand -> {
                            requireAppId(command.appId)
                            requirePermissions(command.permissions, platform)
                        }
                        is SetOrientationCommand -> requireAdapter(
                            runCatching { command.resolvedOrientation() }.getOrNull() in DeviceOrientation.entries,
                            "Orientation must be a literal portrait, landscapeLeft, landscapeRight, or upsideDown.",
                        )
                        is TakeScreenshotCommand -> {
                            if (command.cropOn != null) unsupported()
                            requireAdapter(Regex("[A-Za-z0-9_-]{1,80}").matches(command.path), "Screenshot names must be simple names without directories or extensions.")
                        }
                        is WaitForAnimationToEndCommand -> timeout(command.timeout)
                        else -> unsupported()
                    }
                    expandedCommands += commandCount
                    requireAdapter(expandedCommands <= MAX_EXPANDED_COMMANDS, "Flow exceeds the 1000 expanded-command limit.")
                    if (command is TakeScreenshotCommand) wrapper.copy(takeScreenshotCommand = command.copy(path = ".revyl-maestro/${command.path}")) else validated
                } catch (failure: AdapterFailure) {
                    if (rejectedCommand == null) rejectedCommand = command::class.simpleName
                    throw failure
                }
            }
        }
        return validateCommands(resolvedCommands, 0, true)
    }

    private fun validateConfig(config: MaestroConfig) {
        requireAdapter(config.appId?.matches(appIdPattern) == true, "Use a native appId, not a URL or expression.")
        if (config.ext.isNotEmpty() || config.properties.isNotEmpty() || config.onFlowStart != null || config.onFlowComplete != null) unsupported()
        literal(config.name)
        config.tags?.forEach { literal(it) }
    }

    internal fun allCommands(commands: List<MaestroCommand>, depth: Int = 0): Sequence<MaestroCommand> = sequence {
        requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
        commands.forEach {
            yield(it)
            it.runFlowCommand?.let { nested -> yieldAll(allCommands(nested.commands, depth + 1)) }
        }
    }

    internal fun requiredCapabilities(commands: List<MaestroCommand>, platform: String): Set<WorkerCapability> = allCommands(commands).mapNotNull {
        when (val command = it.asCommand()) {
            is SwipeCommand, is ScrollCommand -> WorkerCapability.EXPLICIT_DRAG_DURATION
            is LaunchAppCommand -> WorkerCapability.APP_LIFECYCLE.takeIf { requiresAppLifecycle(command, platform) }
            is StopAppCommand, is KillAppCommand, is ClearStateCommand, is SetPermissionsCommand -> WorkerCapability.APP_LIFECYCLE
            is ClearKeychainCommand -> WorkerCapability.APP_LIFECYCLE.takeIf { platform == "ios" }
            is SetOrientationCommand -> WorkerCapability.PRECISE_ORIENTATION
            else -> null
        }
    }.toSet()

    private fun requiresAppLifecycle(command: LaunchAppCommand, platform: String): Boolean =
        command.stopApp != false || command.permissions?.isNotEmpty() != false || command.clearState == true ||
            (command.clearKeychain == true && platform == "ios") || !command.launchArguments.isNullOrEmpty()

    internal fun requiresViewerInput(commands: List<MaestroCommand>): Boolean = allCommands(commands).any {
        it.asCommand() is InputTextCommand || it.asCommand() is PasteTextCommand
    }

    private fun tapCount(longPress: Boolean?, repeat: maestro.TapRepeat?): Int {
        if (repeat == null) return 1
        if (longPress == true) unsupported()
        requireAdapter(repeat.repeat in 1..MAX_TAP_COUNT && repeat.delay in 0..MAX_TAP_DELAY_MS && repeat.repeat * repeat.delay <= 120_000, "Tap repeats require 1 to 100 taps, 0 to 10000 milliseconds delay, and at most 120000 milliseconds total delay.")
        return repeat.repeat
    }

    fun prepareScreenshotDirectory(commands: List<MaestroCommand>) {
        val screenshots = allCommands(commands).mapNotNull { it.takeScreenshotCommand }.toList()
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
