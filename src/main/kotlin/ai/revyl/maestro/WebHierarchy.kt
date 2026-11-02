package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
import maestro.TreeNode

internal object WebHierarchy {
    fun parse(node: JsonNode): TreeNode {
        return parseNodes(listOf(node)).single()
    }

    fun parseNodes(nodes: Iterable<JsonNode>): List<TreeNode> {
        var count = 0
        fun parseNode(value: JsonNode, depth: Int): TreeNode {
            requireAdapter(depth <= 100 && ++count <= 10_000, "Web hierarchy exceeds structural limits.")
            requireAdapter(value.isObject && value.path("attributes").isObject && value.path("children").isArray,
                "Revyl returned an invalid web hierarchy node.")
            val source = value.path("attributes")
            requireAdapter(source.path("text").isTextual, "Revyl returned invalid web text.")
            val bounds = source.path("bounds")
            val coordinates = listOf("left", "top", "right", "bottom").map { bounds.path(it) }
            requireAdapter(bounds.isObject && coordinates.all {
                it.isIntegralNumber && it.canConvertToInt() && it.intValue() in -100_000..1_000_000
            }, "Revyl returned invalid web bounds.")
            val (left, top, right, bottom) = coordinates.map { it.intValue() }
            requireAdapter(right >= left && bottom >= top, "Revyl returned negative web dimensions.")
            val attributes = mutableMapOf("text" to source.path("text").textValue(), "bounds" to "[$left,$top][$right,$bottom]")
            source.get("resource-id")?.let {
                requireAdapter(it.isTextual, "Revyl returned an invalid web resource ID.")
                attributes["resource-id"] = it.textValue()
            }
            for (name in listOf("selected", "synthetic", "ignoreBoundsFiltering")) {
                source.get(name)?.let {
                    requireAdapter(it.isBoolean, "Revyl returned an invalid web node state.")
                    attributes[name] = it.booleanValue().toString()
                }
            }
            return TreeNode(attributes = attributes, children = value.path("children").map { parseNode(it, depth + 1) })
        }
        return nodes.map { parseNode(it, 0) }
    }
}
