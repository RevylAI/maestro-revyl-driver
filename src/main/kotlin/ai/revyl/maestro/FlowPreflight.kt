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
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.AssertLightModeCommand
import maestro.orchestra.AssertNoDefectsWithAICommand
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.ExtractTextWithAICommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.ToggleDarkModeCommand
import maestro.orchestra.TravelCommand
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ValidatedFlow(val commands: List<MaestroCommand>, internal val referenceCopies: Map<Path, Path>, internal val dynamicPaths: Map<String, (JsEngine) -> String> = emptyMap())

private val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tiff", ".wbmp", ".heic", ".heif")

private class ArtifactPlan(private val workspaceRoot: Path?, private val compat: Boolean) {
    private val screenshots = mutableSetOf<String>()
    private val outputs = mutableSetOf<String>()
    val referenceCopies = linkedMapOf<Path, Path>()
    val dynamicPaths = linkedMapOf<String, (JsEngine) -> String>()

    private fun dynamic(path: String, resolve: (String) -> String): String {
        val identifier = "__REVYL_ARTIFACT_${UUID.randomUUID().toString().replace("-", "")}"
        dynamicPaths[identifier] = { engine ->
            val value = path.evaluateScripts(engine)
            requireAdapter(!value.contains("\${"), "Artifact expressions must resolve to a path without nested expressions.")
            resolve(value)
        }
        return "\${$identifier}"
    }

    fun screenshot(command: TakeScreenshotCommand): TakeScreenshotCommand {
        if (compat && command.path.contains("\${")) return command.copy(path = dynamic(command.path) { value ->
            val fresh = "$PRIVATE_OUTPUT_DIRECTORY/$value.png" !in outputs
            val resolved = screenshot(command.copy(path = value))
            if (fresh) FlowPreflight.prepareOutput(ValidatedFlow(listOf(MaestroCommand(takeScreenshotCommand = resolved)), emptyMap()), true)
            resolved.path
        })
        requireArtifactPath(command.path)
        screenshots += command.path
        outputs += "$PRIVATE_OUTPUT_DIRECTORY/${command.path}.png"
        return command.copy(path = "$PRIVATE_OUTPUT_DIRECTORY/${command.path}")
    }

    fun recording(command: StartRecordingCommand): StartRecordingCommand {
        if (compat && command.path.contains("\${")) return command.copy(path = dynamic(command.path) { value ->
            val file = "$PRIVATE_OUTPUT_DIRECTORY/$value.mp4"
            val fresh = file !in outputs
            val resolved = recording(command.copy(path = value))
            if (fresh) FlowPreflight.prepareOutput(ValidatedFlow(listOf(MaestroCommand(startRecordingCommand = resolved)), emptyMap()), true)
            resolved.path
        })
        requireArtifactPath(command.path)
        outputs += "$PRIVATE_OUTPUT_DIRECTORY/${command.path}.mp4"
        return command.copy(path = "$PRIVATE_OUTPUT_DIRECTORY/${command.path}")
    }

    fun reference(command: AssertScreenshotCommand): AssertScreenshotCommand {
        if (compat && command.path.contains("\${")) return command.copy(path = dynamic(command.path) { value ->
            val resolved = resolvedReference(command.copy(path = value))
            val target = Path.of(resolved.path)
            referenceCopies[target]?.let { source -> FlowPreflight.prepareOutput(ValidatedFlow(emptyList(), mapOf(target to source)), true) }
            resolved.path
        })
        return resolvedReference(command)
    }

    private fun resolvedReference(command: AssertScreenshotCommand): AssertScreenshotCommand {
        requireAdapter(!command.path.contains("\${") && command.path.isNotBlank() && command.path.none { it.isISOControl() }, "assertScreenshot references must be literal relative paths.")
        val withExtension = if (imageExtensions.any { command.path.endsWith(it, ignoreCase = true) }) command.path else "${command.path}.png"
        val relative = Path.of(withExtension)
        requireAdapter(!relative.isAbsolute, "assertScreenshot references must be literal relative paths.")
        val flowDirectory = command.flowPath ?: throw AdapterFailure("assertScreenshot requires a flow file location.")
        val candidate = flowDirectory.resolve(relative).normalize()
        if (Files.isRegularFile(candidate)) {
            val source = candidate.toRealPath()
            requireAdapter(source.startsWith((workspaceRoot ?: flowDirectory).toRealPath()), "assertScreenshot references must stay inside the flow's Maestro workspace.")
            requireAdapter(Files.size(source) in 1..MAX_MEDIA_FILE_BYTES, "assertScreenshot references must be nonempty files of at most 25 MiB.")
            val target = Path.of(PRIVATE_OUTPUT_DIRECTORY, "references", "${UUID.randomUUID()}${withExtension.substring(withExtension.lastIndexOf('.'))}").toAbsolutePath()
            referenceCopies[target] = source
            return command.copy(path = target.toString())
        }
        val produced = relative.normalize().toString().removeSuffix(".png")
        requireAdapter(withExtension.endsWith(".png") && produced in screenshots,
            "assertScreenshot needs an existing reference inside the flow's workspace or an earlier takeScreenshot with the same path.")
        return command.copy(path = Path.of(PRIVATE_OUTPUT_DIRECTORY, "$produced.png").toAbsolutePath().toString())
    }

    fun media(command: AddMediaCommand) {
        requireAdapter(command.mediaPaths.isNotEmpty(), "addMedia requires at least one file.")
        command.mediaPaths.forEach {
            requireAdapter(!it.contains("\${"), "addMedia files must be literal paths.")
            val file = Path.of(it)
            requireMediaFile(file.toFile())
            workspaceRoot?.let { root -> requireAdapter(file.toRealPath().startsWith(root.toRealPath()), "addMedia files must stay inside the flow's Maestro workspace.") }
        }
    }
}

object FlowPreflight {
    @Volatile var rejectedCommand: String? = null
        private set

    fun validate(commands: List<MaestroCommand>, platform: String): List<MaestroCommand> = validateFlow(commands, platform).commands

    internal fun workspaceRoot(flow: Path): Path {
        val directory = flow.toAbsolutePath().normalize().parent
        return generateSequence(directory) { it.parent }.take(MAX_FLOW_DEPTH).firstOrNull { Files.isRegularFile(it.resolve("config.yaml")) } ?: directory
    }

    fun validateFlow(commands: List<MaestroCommand>, platform: String, compat: Boolean = false, flow: Path? = null): ValidatedFlow {
        rejectedCommand = null
        requireAdapter(platform in setOf("android", "ios", "web"), "Platform must be android, ios, or web.")
        if (platform == "web") allCommands(commands).forEach { wrapper ->
            when (wrapper.asCommand()) {
                is TravelCommand, is SetLocationCommand, is SetOrientationCommand,
                is SetDarkModeCommand, is ToggleDarkModeCommand, is AssertDarkModeCommand, is AssertLightModeCommand,
                is SetAirplaneModeCommand, is ToggleAirplaneModeCommand, is StartRecordingCommand, is StopRecordingCommand, is AddMediaCommand -> unsupported()
                else -> Unit
            }
        }
        val plan = ArtifactPlan(flow?.let(::workspaceRoot), compat)
        if (compat) return ValidatedFlow(mapTree(commands, 0) { compatCommand(it, platform, plan) }, plan.referenceCopies, plan.dynamicPaths)
        FlowSourceOptions.validate(commands)
        val resolvedCommands = FlowParameters.resolve(commands, platform)
        fun selector(value: ElementSelector) = validateSelector(value, platform)
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
                            validateConfig(command.config, platform)
                        }
                        is RunFlowCommand -> {
                            command.condition?.let { condition ->
                                if (condition.scriptCondition != null) unsupported()
                                requireAdapter(condition.platform?.toString()?.lowercase() in setOf(null, "android", "ios", "web"), "Condition platform must be android, ios, or web.")
                                requireAdapter(condition.platform != null || condition.visible != null || condition.notVisible != null, "runFlow requires a nonempty condition.")
                                literal(condition.label)
                                condition.visible?.let(::selector)
                                condition.notVisible?.let(::selector)
                            }
                            literal(command.sourceDescription)
                            command.config?.let { validateConfig(it, platform) }
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
                        is BackPressCommand -> if (platform !in setOf("android", "web")) unsupported()
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
                            requireSwipeDuration(command.duration, platform)
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
                        is OpenLinkCommand -> {
                            requirePlainLink(command.link, command.autoVerify != false, command.browser != false)
                            if (platform == "web") requireAppTarget(command.link, platform)
                        }
                        is LaunchAppCommand -> {
                            requireAppTarget(command.appId, platform)
                            command.permissions?.let { if (platform != "web") requirePermissions(it, platform) }
                            command.launchArguments?.let(::requireLaunchArguments)
                        }
                        is StopAppCommand -> requireAppTarget(command.appId, platform)
                        is KillAppCommand -> requireAppTarget(command.appId, platform)
                        is ClearStateCommand -> requireAppTarget(command.appId, platform)
                        is ClearKeychainCommand -> Unit
                        is SetPermissionsCommand -> {
                            requireAppTarget(command.appId, platform)
                            if (platform != "web") requirePermissions(command.permissions, platform)
                        }
                        is SetOrientationCommand -> requireAdapter(
                            runCatching { command.resolvedOrientation() }.getOrNull() in DeviceOrientation.entries,
                            "Orientation must be a literal portrait, landscapeLeft, landscapeRight, or upsideDown.",
                        )
                        is TakeScreenshotCommand -> {
                            command.cropOn?.let(::selector)
                            validated = wrapper.copy(takeScreenshotCommand = plan.screenshot(command))
                        }
                        is AssertScreenshotCommand -> {
                            command.cropOn?.let(::selector)
                            requireAdapter(command.thresholdPercentage.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true, "assertScreenshot threshold must be a literal percentage from 0 to 100.")
                            validated = wrapper.copy(assertScreenshotCommand = plan.reference(command))
                        }
                        is StartRecordingCommand -> validated = wrapper.copy(startRecordingCommand = plan.recording(command))
                        is StopRecordingCommand, is HideKeyboardCommand, is ToggleDarkModeCommand, is AssertDarkModeCommand, is AssertLightModeCommand -> Unit
                        is AddMediaCommand -> {
                            plan.media(command)
                            commandCount = command.mediaPaths.size
                        }
                        is SetAirplaneModeCommand, is ToggleAirplaneModeCommand -> if (platform != "android") unsupported()
                        is InputRandomCommand -> requireAdapter(command.length == null || command.length in 1..MAX_RANDOM_INPUT_LENGTH, "Random input length must be an integer from 1 to 1000.")
                        is TravelCommand -> commandCount = travelSteps(command)
                        is WaitForAnimationToEndCommand -> timeout(command.timeout)
                        else -> unsupported()
                    }
                    expandedCommands += commandCount
                    requireAdapter(expandedCommands <= MAX_EXPANDED_COMMANDS, "Flow exceeds the 1000 expanded-command limit.")
                    validated
                } catch (failure: AdapterFailure) {
                    if (rejectedCommand == null) rejectedCommand = command::class.simpleName
                    throw failure
                }
            }
        }
        return ValidatedFlow(validateCommands(resolvedCommands, 0, true), plan.referenceCopies)
    }

    private fun travelSteps(command: TravelCommand): Int {
        val points = command.points.map {
            val latitude = it.latitude.toDoubleOrNull() ?: throw AdapterFailure("Travel points must be literal numbers.")
            val longitude = it.longitude.toDoubleOrNull() ?: throw AdapterFailure("Travel points must be literal numbers.")
            requireLocation(latitude, longitude)
            it
        }
        requireAdapter(points.size in 2..MAX_TRAVEL_POINTS, "Travel requires 2 to 20 points.")
        val speed = command.speedMPS ?: 4.0
        requireAdapter(speed.isFinite() && speed in 0.1..MAX_TRAVEL_SPEED_MPS, "Travel speed must be from 0.1 to 10000 meters per second.")
        val durationMs = points.zipWithNext { start, end -> start.getDistanceInMeters(end) }.sum() / speed * 1_000
        requireAdapter(durationMs <= MAX_TRAVEL_DURATION_MS, "Travel must finish within 1800000 milliseconds at the requested speed.")
        return 1 + TRAVEL_STEPS_PER_SEGMENT * (points.size - 1)
    }

    private fun compatCommand(wrapper: MaestroCommand, platform: String, plan: ArtifactPlan): MaestroCommand {
        val command = wrapper.asCommand() ?: unsupported()
        try {
            return when (command) {
                is AssertWithAICommand, is AssertNoDefectsWithAICommand, is ExtractTextWithAICommand -> unsupported()
                is BackPressCommand -> if (platform !in setOf("android", "web")) unsupported() else wrapper
                is SetAirplaneModeCommand, is ToggleAirplaneModeCommand -> if (platform != "android") unsupported() else wrapper
                is PressKeyCommand -> wrapper.also { requireSupportedKey(command.code, platform) }
                is OpenLinkCommand -> wrapper.also {
                    if (platform == "web" && !command.link.contains("\${")) requireAppTarget(command.link, platform)
                }
                is SwipeCommand -> wrapper.also { requireSwipeDuration(command.duration, platform) }
                is TakeScreenshotCommand -> wrapper.copy(takeScreenshotCommand = plan.screenshot(command))
                is StartRecordingCommand -> wrapper.copy(startRecordingCommand = plan.recording(command))
                is AssertScreenshotCommand -> wrapper.copy(assertScreenshotCommand = plan.reference(command))
                is AddMediaCommand -> wrapper.also { plan.media(command) }
                else -> wrapper
            }
        } catch (failure: AdapterFailure) {
            if (rejectedCommand == null) rejectedCommand = command::class.simpleName
            throw failure
        }
    }

    private fun mapTree(commands: List<MaestroCommand>, depth: Int, transform: (MaestroCommand) -> MaestroCommand): List<MaestroCommand> {
        requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
        fun hooks(config: MaestroConfig?): MaestroConfig? = config?.copy(
            onFlowStart = config.onFlowStart?.let { MaestroOnFlowStart(mapTree(it.commands, depth + 1, transform)) },
            onFlowComplete = config.onFlowComplete?.let { MaestroOnFlowComplete(mapTree(it.commands, depth + 1, transform)) },
        )
        return commands.map { original ->
            val wrapper = transform(original)
            val runFlow = wrapper.runFlowCommand
            val repeat = wrapper.repeatCommand
            val retry = wrapper.retryCommand
            val configuration = wrapper.applyConfigurationCommand
            when {
                runFlow != null -> wrapper.copy(runFlowCommand = runFlow.copy(commands = mapTree(runFlow.commands, depth + 1, transform), config = hooks(runFlow.config)))
                repeat != null -> wrapper.copy(repeatCommand = repeat.copy(commands = mapTree(repeat.commands, depth + 1, transform)))
                retry != null -> wrapper.copy(retryCommand = retry.copy(commands = mapTree(retry.commands, depth + 1, transform), config = hooks(retry.config)))
                configuration != null -> wrapper.copy(applyConfigurationCommand = configuration.copy(config = hooks(configuration.config)!!))
                else -> wrapper
            }
        }
    }

    private fun validateConfig(config: MaestroConfig, platform: String) {
        val appId = config.appId ?: throw AdapterFailure("Flow target is required.")
        requireAppTarget(appId, platform)
        if (config.ext.keys.any { it != "androidWebViewHierarchy" } || config.properties.isNotEmpty() || config.onFlowStart != null || config.onFlowComplete != null) unsupported()
        config.ext["androidWebViewHierarchy"]?.let { requireAdapter(it in setOf("devtools", "native"), "androidWebViewHierarchy must be devtools or native.") }
        literal(config.name)
        config.tags?.forEach { literal(it) }
    }

    internal fun allCommands(commands: List<MaestroCommand>, depth: Int = 0): Sequence<MaestroCommand> = sequence {
        requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
        commands.forEach {
            yield(it)
            val config = it.applyConfigurationCommand?.config ?: it.runFlowCommand?.config ?: it.retryCommand?.config
            listOfNotNull(config?.onFlowStart?.commands, it.runFlowCommand?.commands, it.repeatCommand?.commands, it.retryCommand?.commands, config?.onFlowComplete?.commands)
                .forEach { nested -> yieldAll(allCommands(nested, depth + 1)) }
        }
    }

    internal fun requiredCapabilities(commands: List<MaestroCommand>, platform: String): Set<WorkerCapability> = buildSet {
        allCommands(commands).forEach { wrapper ->
            val command = wrapper.asCommand()
            if (platform == "web") {
                val config = wrapper.applyConfigurationCommand?.config ?: wrapper.runFlowCommand?.config ?: wrapper.retryCommand?.config
                requireAdapter(config?.ext?.get("androidWebViewHierarchy") != "devtools", "DevTools WebView hierarchy is Android-only.")
                add(WorkerCapability.MAESTRO_WEB)
                return@forEach
            }
            when (command) {
                is KillAppCommand -> if (platform == "android") add(WorkerCapability.PROCESS_DEATH_KILL)
                is LaunchAppCommand -> if (platform == "ios" && requiresExtendedIosPermissions(command.permissions)) add(WorkerCapability.EXTENDED_IOS_PERMISSIONS)
                is SetPermissionsCommand -> if (platform == "ios" && requiresExtendedIosPermissions(command.permissions)) add(WorkerCapability.EXTENDED_IOS_PERMISSIONS)
                else -> Unit
            }
            val config = wrapper.applyConfigurationCommand?.config ?: wrapper.runFlowCommand?.config ?: wrapper.retryCommand?.config
            if (config?.ext?.get("androidWebViewHierarchy") == "devtools") {
                requireAdapter(platform == "android", "DevTools WebView hierarchy is Android-only.")
                add(WorkerCapability.WEBVIEW_HIERARCHY)
            }
            val capability = when (command) {
                is SwipeCommand, is ScrollCommand, is ScrollUntilVisibleCommand -> WorkerCapability.EXPLICIT_DRAG_DURATION
                is LaunchAppCommand -> WorkerCapability.APP_LIFECYCLE.takeIf { requiresAppLifecycle(command, platform) }
                is StopAppCommand, is ClearStateCommand, is SetPermissionsCommand -> WorkerCapability.APP_LIFECYCLE
                is KillAppCommand -> WorkerCapability.APP_LIFECYCLE.takeIf { platform == "ios" }
                is ClearKeychainCommand -> WorkerCapability.APP_LIFECYCLE.takeIf { platform == "ios" }
                is SetOrientationCommand -> WorkerCapability.PRECISE_ORIENTATION
                is HideKeyboardCommand -> WorkerCapability.KEYBOARD_CONTROL
                is AddMediaCommand -> WorkerCapability.ADD_MEDIA
                is ToggleDarkModeCommand, is AssertDarkModeCommand, is AssertLightModeCommand -> WorkerCapability.APPEARANCE_READ
                is SetAirplaneModeCommand, is ToggleAirplaneModeCommand -> WorkerCapability.AIRPLANE_MODE
                is StartRecordingCommand, is StopRecordingCommand -> WorkerCapability.SCREEN_RECORDING
                else -> null
            }
            capability?.let(::add)
        }
    }

    internal fun optionalCapabilities(commands: List<MaestroCommand>, platform: String): Set<WorkerCapability> {
        if (platform != "ios") return emptySet()
        return if (allCommands(commands).any {
            when (val command = it.asCommand()) {
                is LaunchAppCommand -> command.permissions?.containsKey("all") != false
                is SetPermissionsCommand -> "all" in command.permissions
                is ClearStateCommand -> true
                else -> false
            }
        }) setOf(WorkerCapability.EXTENDED_IOS_PERMISSIONS) else emptySet()
    }

    private fun requiresAppLifecycle(command: LaunchAppCommand, platform: String): Boolean =
        command.stopApp != false || command.permissions?.isNotEmpty() != false || command.clearState == true ||
            (command.clearKeychain == true && platform == "ios") || !command.launchArguments.isNullOrEmpty()

    internal fun requiresViewerInput(commands: List<MaestroCommand>): Boolean = allCommands(commands).any {
        it.asCommand() is InputTextCommand || it.asCommand() is PasteTextCommand || it.asCommand() is InputRandomCommand
    }

    private fun tapCount(longPress: Boolean?, repeat: maestro.TapRepeat?): Int {
        if (repeat == null) return 1
        if (longPress == true) unsupported()
        requireAdapter(repeat.repeat in 1..MAX_TAP_COUNT && repeat.delay in 0..MAX_TAP_DELAY_MS && repeat.repeat * repeat.delay <= 120_000, "Tap repeats require 1 to 100 taps, 0 to 10000 milliseconds delay, and at most 120000 milliseconds total delay.")
        return repeat.repeat
    }

    fun prepareScreenshotDirectory(commands: List<MaestroCommand>) = prepareOutput(ValidatedFlow(commands, emptyMap()), compat = false)

    fun prepareOutput(flow: ValidatedFlow, compat: Boolean) {
        val artifacts = allCommands(flow.commands).mapNotNull {
            it.takeScreenshotCommand?.let { command -> "${command.path}.png" } ?: it.startRecordingCommand?.let { command -> "${command.path}.mp4" }
        }.filterNot { compat && it.contains("\${") }.toList().let { if (compat) it.distinct() else it }
        if (artifacts.isEmpty() && flow.referenceCopies.isEmpty()) return
        val root = Path.of(PRIVATE_OUTPUT_DIRECTORY)
        val directoryPermissions = PosixFilePermissions.fromString("rwx------")
        fun privateDirectory(directory: Path) {
            requireAdapter(!Files.isSymbolicLink(directory), "Private output directories must not be symlinks.")
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(directoryPermissions))
            requireAdapter(Files.isDirectory(directory, NOFOLLOW_LINKS), "Screenshot output must be a private directory.")
            Files.setPosixFilePermissions(directory, directoryPermissions)
        }
        fun privateFile(file: Path): Path {
            val relative = root.toAbsolutePath().relativize(file.toAbsolutePath().normalize())
            requireAdapter(!relative.startsWith(".."), "Private output must stay inside the output directory.")
            var directory = root
            privateDirectory(directory)
            relative.parent?.forEach { directory = directory.resolve(it); privateDirectory(directory) }
            requireAdapter(!Files.exists(file, NOFOLLOW_LINKS), "Screenshot or recording output already exists; choose a new name or remove the private artifact.")
            return Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        privateDirectory(root)
        artifacts.forEach { privateFile(Path.of(it)) }
        flow.referenceCopies.forEach { (target, source) -> Files.write(privateFile(target), Files.readAllBytes(source)) }
    }

    private fun timeout(value: String?) {
        if (value != null) requireAdapter(value.toLongOrNull() in 1L..120_000L, "Command timeout must be a literal integer from 1 to 120000 milliseconds.")
    }

    private fun literal(value: String?) {
        if (value?.contains("\${") == true) unsupported()
    }

    private fun validateSelector(value: ElementSelector, platform: String) {
        if (value.optional || (value.css != null && platform != "web")) unsupported()
        literal(value.css)
        literal(value.textRegex)
        literal(value.idRegex)
        literal(value.index)
        value.index?.let { requireAdapter(it.toIntOrNull() in 0..10_000, "Selector index must be a literal non-negative integer.") }
        listOf(value.below, value.above, value.leftOf, value.rightOf, value.containsChild, value.childOf).filterNotNull().forEach { validateSelector(it, platform) }
        value.containsDescendants?.forEach { validateSelector(it, platform) }
    }
}
