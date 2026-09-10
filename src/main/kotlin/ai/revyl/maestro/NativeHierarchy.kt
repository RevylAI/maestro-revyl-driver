package ai.revyl.maestro

import com.fasterxml.jackson.databind.JsonNode
import maestro.TreeNode
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

data class NativeHierarchy(val root: TreeNode, val widthPoints: Int?, val heightPoints: Int?) {
    companion object {
        fun parse(bytes: ByteArray, platform: String): NativeHierarchy = try {
            val parser = HierarchyParser()
            when (platform) {
                "android" -> parser.android(bytes)
                "ios" -> parser.ios(bytes)
                else -> unsupported()
            }
        } catch (_: Exception) {
            throw AdapterFailure("Revyl returned an invalid native hierarchy.")
        }
    }
}

private class HierarchyParser {
    private var count = 0

    private fun visit(depth: Int) {
        requireAdapter(depth <= 100 && ++count <= 10_000, "Native hierarchy exceeds structural limits.")
    }

    fun android(bytes: ByteArray): NativeHierarchy {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException): Nothing = throw exception
            override fun error(exception: SAXParseException): Nothing = throw exception
            override fun fatalError(exception: SAXParseException): Nothing = throw exception
        })
        val document = builder.parse(ByteArrayInputStream(bytes)).documentElement
        requireAdapter(document.tagName == "hierarchy", "Expected UIAutomator hierarchy.")
        return NativeHierarchy(TreeNode(children = androidChildren(document, 0)), null, null)
    }

    private fun androidChildren(parent: Element, depth: Int): List<TreeNode> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            visit(depth)
            requireAdapter(element.tagName == "node", "Expected UIAutomator node.")
            val bounds = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
                .matchEntire(element.getAttribute("bounds"))?.groupValues?.drop(1)?.map { it.toInt() }
                ?: throw AdapterFailure("Missing native bounds.")
            requireAdapter(bounds[2] >= bounds[0] && bounds[3] >= bounds[1] && bounds.all { it in -100_000..100_000 }, "Invalid native bounds.")
            fun state(name: String): Boolean? {
                if (!element.hasAttribute(name)) return null
                return element.getAttribute(name).toBooleanStrictOrNull() ?: throw AdapterFailure("Invalid native state.")
            }
            val attributes = mutableMapOf("bounds" to element.getAttribute("bounds"))
            for ((source, target) in listOf("text" to "text", "resource-id" to "resource-id", "content-desc" to "accessibilityText", "class" to "class", "hint" to "hintText")) {
                if (element.hasAttribute(source)) attributes[target] = element.getAttribute(source)
            }
            add(TreeNode(attributes, androidChildren(element, depth + 1), state("clickable"), state("enabled"), state("focused"), state("checked"), state("selected")))
        }
    }

    fun ios(bytes: ByteArray): NativeHierarchy {
        val roots = RevylClient.readJson(bytes)
        requireAdapter(roots.isArray && !roots.isEmpty, "Expected IDB hierarchy roots.")
        val nodes = roots.map { iosNode(it, 0) }
        val frames = roots.map { it.path("frame") }
        val viewport = frames.maxBy { coordinate(it, "width") * coordinate(it, "height") }
        requireAdapter(coordinate(viewport, "x") == 0.0 && coordinate(viewport, "y") == 0.0, "IDB hierarchy must include a full-screen root frame.")
        val width = coordinate(viewport, "width").roundToInt()
        val height = coordinate(viewport, "height").roundToInt()
        requireAdapter(width > 0 && height > 0, "Invalid IDB viewport.")
        return NativeHierarchy(TreeNode(children = nodes), width, height)
    }

    private fun coordinate(frame: JsonNode, name: String): Double {
        val value = frame.path(name)
        requireAdapter(value.isNumber && value.doubleValue().isFinite() && value.doubleValue() in -100_000.0..100_000.0, "Invalid IDB frame.")
        return value.doubleValue()
    }

    private fun iosNode(node: JsonNode, depth: Int): TreeNode {
        visit(depth)
        requireAdapter(node.isObject, "Invalid IDB node.")
        fun text(name: String): String? {
            val value = node.get(name) ?: return null
            if (value.isNull) return null
            requireAdapter(value.isTextual, "Invalid IDB string.")
            return value.textValue()
        }
        fun state(name: String): Boolean? {
            val value = node.get(name) ?: return null
            if (value.isNull) return null
            requireAdapter(value.isBoolean, "Invalid IDB state.")
            return value.booleanValue()
        }
        val frame = node.path("frame")
        val x = coordinate(frame, "x")
        val y = coordinate(frame, "y")
        val width = coordinate(frame, "width")
        val height = coordinate(frame, "height")
        requireAdapter(width >= 0 && height >= 0, "Invalid IDB dimensions.")
        val type = text("type")
        val editable = type in setOf("TextField", "SecureTextField", "TextView")
        val attributes = mutableMapOf("bounds" to "[${x.roundToInt()},${y.roundToInt()}][${(x + width).roundToInt()},${(y + height).roundToInt()}]")
        text("AXUniqueId")?.let { attributes["resource-id"] = it }
        text("AXLabel")?.let { attributes["accessibilityText"] = it }
        (if (editable) text("AXValue") else text("AXLabel") ?: text("AXValue"))?.let { attributes["text"] = it }
        type?.let { attributes["class"] = it }
        val children = node.get("children")
        requireAdapter(children == null || children.isArray, "Invalid IDB children.")
        return TreeNode(
            attributes = attributes,
            children = children?.map { iosNode(it, depth + 1) }.orEmpty(),
            enabled = state("enabled"),
            focused = state("focused"),
            checked = state("checked"),
            selected = state("selected"),
        )
    }
}
