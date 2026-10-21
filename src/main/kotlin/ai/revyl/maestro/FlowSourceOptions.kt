package ai.revyl.maestro

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import maestro.orchestra.MaestroCommand
import java.nio.file.Files
import java.nio.file.Path

internal object FlowSourceOptions {
    private val mapper = ObjectMapper(YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))

    fun validate(commands: List<MaestroCommand>) {
        val sources = FlowPreflight.allCommands(commands).mapNotNull { it.sourceInfo?.source }.distinct().toList()
        requireAdapter(sources.isNotEmpty(), "Flow source is required.")
        sources.forEach { validateDocument(it) { body -> validateBody(body, 0) { _, _ -> } } }
    }

    fun validate(path: Path) {
        val activeFiles = mutableSetOf<Path>()
        var commandCount = 0
        var sourceBytes = 0L
        fun visit(path: Path, depth: Int) {
            requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
            requireAdapter(Files.isRegularFile(path) && Files.size(path) <= 1024 * 1024, "Flow must be a regular file no larger than 1 MiB.")
            requireAdapter(activeFiles.add(path.toRealPath()), "Cyclic subflow references are unsupported.")
            sourceBytes += Files.size(path)
            requireAdapter(sourceBytes <= 8 * 1024 * 1024, "Flow exceeds the 8 MiB total source limit.")
            validateDocument(Files.readString(path)) { body ->
                validateBody(body, depth, onCommand = {
                    commandCount++
                    requireAdapter(commandCount <= MAX_SOURCE_COMMANDS, "Flow exceeds the 999 source-command limit.")
                }) { file, nestedDepth -> visit(path.resolveSibling(file).toAbsolutePath().normalize(), nestedDepth) }
            }
            activeFiles.remove(path.toRealPath())
        }
        visit(path.toAbsolutePath().normalize(), 0)
    }

    private fun validateDocument(source: String, validateBody: (JsonNode) -> Unit) {
        mapper.readerFor(JsonNode::class.java).readValues<JsonNode>(source).use { documents ->
            requireAdapter(documents.hasNextValue(), "Flow configuration is required.")
            val config = documents.nextValue()
            requireAdapter(config.isObject, "Flow configuration is required.")
            if (config.fieldNames().asSequence().any { it !in setOf("appId", "name", "tags", "env", "androidWebViewHierarchy") }) unsupported()
            config.get("androidWebViewHierarchy")?.let { requireAdapter(it.isTextual && it.textValue() in setOf("devtools", "native"), "androidWebViewHierarchy must be devtools or native.") }
            validateTemplates(config)
            config.get("env")?.let(::validateEnv)
            requireAdapter(documents.hasNextValue(), "Flow commands are required.")
            val body = documents.nextValue()
            requireAdapter(body.isArray && !documents.hasNextValue(), "Use exactly one flow configuration and command list.")
            validateTemplates(body)
            validateBody(body)
        }
    }

    private fun validateBody(body: JsonNode, depth: Int, onCommand: () -> Unit = {}, visitFile: (String, Int) -> Unit) {
        requireAdapter(depth <= MAX_FLOW_DEPTH, "Flow exceeds the 16-level subflow depth limit.")
        requireAdapter(body.isArray && !body.isEmpty, "A flow must contain commands.")
        body.forEach { node ->
            onCommand()
            val name = if (node.isTextual) node.textValue() else node.fieldNames().asSequence().firstOrNull()
            if (name in setOf("runScript", "evalScript", "repeat", "retry")) unsupported()
            requireAdapter(node.isTextual || (node.isObject && node.size() == 1), "Use one command per list item.")
            if (node.isTextual) return@forEach
            val entry = node.fields().next()
            val options = entry.value
            when (entry.key) {
                "runFlow" -> {
                    val file = if (options.isTextual) options else options.get("file")
                    if (!options.isTextual) {
                        requireAdapter(options.isObject, "runFlow requires a file or inline commands.")
                        if (options.fieldNames().asSequence().any { it !in setOf("file", "commands", "label", "env", "when") }) unsupported()
                        options.get("label")?.let { if (!it.isTextual) unsupported() }
                        options.get("env")?.let(::validateEnv)
                        options.get("when")?.let { condition ->
                            requireAdapter(condition.isObject && !condition.isEmpty, "runFlow conditions require visible, notVisible, or platform.")
                            if (condition.fieldNames().asSequence().any { it !in setOf("visible", "notVisible", "platform", "label") }) unsupported()
                            requireAdapter(listOf("visible", "notVisible", "platform").any(condition::has), "runFlow conditions require visible, notVisible, or platform.")
                            condition.get("platform")?.let { requireAdapter(it.isTextual && it.textValue().lowercase() in setOf("android", "ios"), "Condition platform must be android or ios.") }
                            condition.get("label")?.let { requireAdapter(it.isTextual && !it.textValue().contains("\${"), "Condition labels must be literal text.") }
                            listOf("visible", "notVisible").forEach { name ->
                                condition.get(name)?.let {
                                    requireAdapter(it.isTextual || (it.isObject && !it.isEmpty), "Condition selectors must be text or nonempty selector options.")
                                    selectorOptions(it, false)
                                }
                            }
                        }
                    }
                    requireAdapter((file != null) != options.has("commands"), "runFlow requires exactly one file or inline command list.")
                    if (file != null) {
                        requireAdapter(file.isTextual && file.textValue().isNotBlank() && !file.textValue().contains("\${"), "Subflow paths must be nonempty literal paths.")
                        visitFile(file.textValue(), depth + 1)
                    } else validateBody(options.get("commands"), depth + 1, onCommand, visitFile)
                }
                "tapOn", "doubleTapOn", "longPressOn" -> if (options.isObject) {
                    selectorOptions(options, true)
                    options.get("repeat")?.let {
                        requireAdapter(entry.key == "tapOn" && it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 1..MAX_TAP_COUNT, "Tap repeat must be an integer from 1 to 100 on tapOn.")
                    }
                    options.get("delay")?.let {
                        requireAdapter(entry.key != "longPressOn" && (entry.key == "doubleTapOn" || options.has("repeat")) && it.isIntegralNumber && it.canConvertToLong() && it.longValue() in 0..MAX_TAP_DELAY_MS, "Tap delay requires an intentional repeat and must be from 0 to 10000 milliseconds.")
                    }
                    options.get("waitToSettleTimeoutMs")?.let {
                        requireAdapter(it.isIntegralNumber && it.canConvertToLong() && it.longValue() in 1..120_000, "Settle timeout must be an integer from 1 to 120000 milliseconds.")
                    }
                }
                "copyTextFrom" -> selectorOptions(options, false)
                "inputText" -> {
                    if (options.isObject) {
                        if (options.fieldNames().asSequence().any { it !in setOf("text", "label", "optional") }) unsupported()
                        requireAdapter(options.path("text").isTextual, "inputText requires literal text.")
                        options.get("optional")?.let { if (!it.isBoolean || it.booleanValue()) unsupported() }
                        options.get("label")?.let { if (!it.isTextual || it.textValue().contains("\${")) unsupported() }
                    } else requireAdapter(options.isTextual, "inputText requires literal text.")
                }
                "pasteText" -> {
                    requireAdapter(options.isNull || options.isObject, "pasteText accepts only supported command options.")
                    if (options.isObject) {
                        if (options.fieldNames().asSequence().any { it !in setOf("label", "optional") }) unsupported()
                        options.get("optional")?.let { if (!it.isBoolean || it.booleanValue()) unsupported() }
                        options.get("label")?.let { if (!it.isTextual || it.textValue().contains("\${")) unsupported() }
                    }
                }
                "swipe" -> {
                    requireAdapter(options.isObject, "Swipe requires explicit options.")
                    if (options.fieldNames().asSequence().any { it !in setOf("start", "end", "direction", "from", "duration", "label", "optional", "waitToSettleTimeoutMs") }) unsupported()
                    options.get("optional")?.let { if (!it.isBoolean || it.booleanValue()) unsupported() }
                    options.get("duration")?.let {
                        requireAdapter(it.isIntegralNumber && it.canConvertToLong(), "Swipe duration must be a literal integer.")
                        requireSwipeDuration(it.longValue())
                    }
                    options.get("waitToSettleTimeoutMs")?.let {
                        requireAdapter(it.isIntegralNumber && it.canConvertToLong() && it.longValue() in 1..120_000, "Settle timeout must be an integer from 1 to 120000 milliseconds.")
                    }
                    options.get("from")?.let {
                        if (options.has("start") || options.has("end") || !options.has("direction")) unsupported()
                        selectorOptions(it, false)
                    }
                    listOf("start", "end").forEach { key ->
                        options.get(key)?.let {
                            requireAdapter(it.isTextual && Regex("\\d{1,5},\\s*\\d{1,5}|\\d{1,2}%,\\s*\\d{1,2}%").matches(it.textValue()), "Use a literal native or percent swipe coordinate pair.")
                        }
                    }
                }
                "eraseText" -> {
                    val count = if (options.isObject) options.get("charactersToErase") else options
                    if (count != null) requireAdapter(count.isIntegralNumber && count.canConvertToInt() && count.intValue() in 1..MAX_ERASE_CHARACTERS, "Erase count must be an integer from 1 to 100.")
                }
                "inputRandomText", "inputRandomNumber", "inputRandomEmail", "inputRandomPersonName", "inputRandomCityName", "inputRandomCountryName", "inputRandomColorName" -> {
                    options.get("length")?.let { requireAdapter(it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 1..MAX_RANDOM_INPUT_LENGTH, "Random input length must be an integer from 1 to 1000.") }
                }
                "travel" -> {
                    options.get("speed")?.let { requireAdapter(it.isNumber, "Travel speed must be a literal number.") }
                }
                "setDarkMode" -> {
                    if (options.isObject) {
                        if (options.fieldNames().asSequence().any { it !in setOf("value", "label", "optional") }) unsupported()
                        requireAdapter(options.path("value").isTextual && options.path("value").textValue() in setOf("enabled", "disabled"), "Dark mode must be enabled or disabled.")
                        options.get("optional")?.let { if (!it.isBoolean || it.booleanValue()) unsupported() }
                        options.get("label")?.let { if (!it.isTextual || it.textValue().contains("\${")) unsupported() }
                    } else requireAdapter(options.isTextual && options.textValue() in setOf("enabled", "disabled"), "Dark mode must be enabled or disabled.")
                }
            }
        }
    }

    private fun selectorOptions(options: JsonNode, tapOptionsAllowed: Boolean) {
        if (!options.isObject) return
        if (options.has("start") || options.has("end")) unsupported()
        if (!tapOptionsAllowed && options.fieldNames().asSequence().any {
            it in setOf("repeat", "delay", "point", "retryTapIfNoChange", "waitUntilVisible", "waitToSettleTimeoutMs")
        }) unsupported()
        listOf("below", "above", "leftOf", "rightOf", "containsChild", "childOf").forEach { key ->
            options.get(key)?.let { selectorOptions(it, false) }
        }
        options.get("containsDescendants")?.forEach { selectorOptions(it, false) }
    }

    private fun validateEnv(env: JsonNode) {
        requireAdapter(env.isObject && env.size() <= 64, "env must be a map of at most 64 string parameters.")
        env.fields().forEach { (name, value) ->
            FlowParameters.requireName(name)
            requireAdapter(value.isTextual && value.textValue().toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES, "env values must be strings of at most 16 KiB.")
        }
    }

    private fun validateTemplates(node: JsonNode) {
        if (node.isTextual) FlowParameters.requireTemplate(node.textValue())
        if (node.isObject) node.fieldNames().forEach { requireAdapter(!it.contains("\${"), "Parameter references in option names are unsupported.") }
        node.forEach(::validateTemplates)
    }
}
