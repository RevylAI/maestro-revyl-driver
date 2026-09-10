package ai.revyl.maestro

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import maestro.orchestra.MaestroCommand

internal object FlowSourceOptions {
    private val mapper = ObjectMapper(YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))

    fun validate(commands: List<MaestroCommand>) {
        val source = commands.firstNotNullOfOrNull { it.sourceInfo?.source } ?: unsupported()
        mapper.readerFor(JsonNode::class.java).readValues<JsonNode>(source).use { documents ->
            requireAdapter(documents.hasNextValue(), "Flow configuration is required.")
            documents.nextValue()
            requireAdapter(documents.hasNextValue(), "Flow commands are required.")
            val body = documents.nextValue()
            requireAdapter(body.isArray && !documents.hasNextValue(), "Use exactly one flow configuration and command list.")
            body.forEach { node ->
                if (!node.isObject || node.size() != 1) return@forEach
                val entry = node.fields().next()
                val options = entry.value
                when (entry.key) {
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
}
