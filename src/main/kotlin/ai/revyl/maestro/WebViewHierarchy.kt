package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
import maestro.TreeNode
import kotlin.math.roundToInt

internal fun parseWebViewNodes(response: JsonNode): List<TreeNode> {
    requireAdapter(response.isObject && response.size() == 1, "Revyl returned an invalid WebView hierarchy.")
    val roots = response.path("nodes")
    requireAdapter(roots.isArray, "Revyl returned an invalid WebView hierarchy.")
    var count = 0
    fun parse(node: JsonNode, depth: Int): List<TreeNode> {
        requireAdapter(depth <= 100 && ++count <= 10_000, "WebView hierarchy exceeds structural limits.")
        requireAdapter(node.isObject && node.path("text").isTextual && node.path("id").isTextual && node.path("children").isArray,
            "Revyl returned an invalid WebView node.")
        val bounds = node.path("bounds")
        val coordinates = listOf("x", "y", "width", "height").map { bounds.path(it) }
        requireAdapter(bounds.isObject && coordinates.all { it.isNumber && it.doubleValue().isFinite() && it.doubleValue() in -100_000.0..100_000.0 },
            "Revyl returned invalid WebView bounds.")
        val values = coordinates.map { it.doubleValue() }
        val points = listOf(values[0], values[1], values[0] + values[2], values[1] + values[3]).map { it.roundToInt() }
        val children = node.path("children").flatMap { parse(it, depth + 1) }
        if (points[2] <= points[0] || points[3] <= points[1]) return children
        return listOf(TreeNode(
            attributes = mutableMapOf("text" to node.path("text").textValue(), "resource-id" to node.path("id").textValue(),
                "bounds" to "[${points[0]},${points[1]}][${points[2]},${points[3]}]"),
            children = children,
            enabled = true,
        ))
    }
    return roots.flatMap { parse(it, 0) }
}
