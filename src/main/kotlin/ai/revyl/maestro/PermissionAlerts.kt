package ai.revyl.maestro

import maestro.TreeNode
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val PERMISSION_ALERT_WINDOW_MS = 60_000L
internal const val PERMISSION_ALERT_MAX_ANSWERS_PER_LAUNCH = 8
internal const val PERMISSION_ALERT_SETTLE_MS = 750L
private const val ALERT_BUTTON_REACH_POINTS = 200.0
private const val ALERT_TEXT_REACH_POINTS = 320.0

private val refusalLabels = setOf("Don't Allow", "Ask App Not to Track", "Keep Only While Using")
private val acceptanceLabels = setOf("Allow", "Allow Once", "Allow While Using App", "Allow Full Access", "Change to Always Allow", "OK")
private val buttonsByAnswer = mapOf(
    "allow" to listOf("Change to Always Allow", "Allow While Using App", "Allow Full Access", "Allow", "OK"),
    "deny" to listOf("Don't Allow", "Ask App Not to Track", "Keep Only While Using"),
)
private val permissionByAlertPhrase = listOf(
    "track your activity" to "userTracking",
    "send you notifications" to "notifications",
    "use your location" to "location",
    "motion & fitness" to "motion",
    "access the camera" to "camera",
    "access the microphone" to "microphone",
    "access your contacts" to "contacts",
    "your calendar" to "calendar",
    "your reminders" to "reminders",
    "your photos" to "photos",
    "apple music" to "medialibrary",
    "use bluetooth" to "bluetooth",
    "speech recognition" to "speech",
    "use siri" to "siri",
)
private val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

internal data class PermissionAlertTap(val permission: String, val answer: String, val x: Int, val y: Int)

private data class LabeledPoint(val label: String, val x: Double, val y: Double) {
    fun distanceTo(other: LabeledPoint): Double = hypot(x - other.x, y - other.y)
}

/**
 * Finds the button that answers an iOS permission alert the way the flow asked.
 *
 * An alert is a refusal button with an acceptance button within one alert's
 * reach. Its permission comes from the system wording of nearby text, and an
 * alert for a permission the flow did not name is left on screen.
 */
internal fun findPermissionAlertTap(root: TreeNode, answers: Map<String, String>): PermissionAlertTap? {
    if (answers.isEmpty()) return null
    val buttons = mutableListOf<LabeledPoint>()
    val texts = mutableListOf<LabeledPoint>()
    collectLabeledPoints(root, buttons, texts)
    val acceptances = buttons.filter { it.label in acceptanceLabels }
    for (refusal in buttons.filter { it.label in refusalLabels }) {
        if (acceptances.none { it.distanceTo(refusal) <= ALERT_BUTTON_REACH_POINTS }) continue
        val nearbyText = texts.filter { it.distanceTo(refusal) <= ALERT_TEXT_REACH_POINTS }.map { it.label.lowercase() }
        val permission = permissionByAlertPhrase.firstOrNull { (phrase, _) -> nearbyText.any { phrase in it } }?.second ?: continue
        val answer = answers[permission] ?: continue
        for (label in buttonsByAnswer.getValue(answer)) {
            val button = buttons.filter { it.label == label && it.distanceTo(refusal) <= ALERT_BUTTON_REACH_POINTS }
                .minByOrNull { it.distanceTo(refusal) } ?: continue
            return PermissionAlertTap(permission, answer, button.x.roundToInt(), button.y.roundToInt())
        }
    }
    return null
}

private fun collectLabeledPoints(node: TreeNode, buttons: MutableList<LabeledPoint>, texts: MutableList<LabeledPoint>) {
    node.children.forEach { collectLabeledPoints(it, buttons, texts) }
    val label = (node.attributes["text"] ?: node.attributes["accessibilityText"])
        ?.replace('\u2019', '\'')?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")
        ?.takeIf { it.isNotEmpty() } ?: return
    val bounds = node.attributes["bounds"]?.let { boundsPattern.matchEntire(it) }?.groupValues?.drop(1)?.map { it.toDouble() } ?: return
    if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) return
    val point = LabeledPoint(label, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2)
    if (node.attributes["class"] == "Button") buttons.add(point) else texts.add(point)
}

/** Records the per-permission answers the latest iOS setPermissions calls asked for. */
internal fun MutableMap<String, String>.recordPermissionAnswers(permissions: Map<String, String>) {
    if (permissions["all"] == "unset") clear()
    permissions.forEach { (name, value) ->
        when {
            name == "all" -> Unit
            value == "unset" -> remove(name)
            else -> this[name] = value
        }
    }
}
