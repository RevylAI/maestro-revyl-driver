package ai.revyl.maestro

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.OnDeviceElementQuery
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.android.chromedevtools.AndroidWebViewHierarchyClient
import okio.Sink
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import javax.imageio.ImageIO

class RevylDriver(private val client: RevylClient, private val platform: String) : Driver {
    private var closed = false
    private var chromeDevToolsEnabled = false
    private var recording: ScreenRecording? = null
    private val recordingTimer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "revyl-recording-deadline").apply { isDaemon = true } }

    override fun name(): String = "Revyl (native subset)"
    override fun open() { requireAdapter(!closed, "The local Revyl driver is closed.") }
    override fun close() {
        try { recording?.close() } finally { closed = true; recordingTimer.shutdownNow(); client.close() }
    }
    override fun isShutdown(): Boolean = closed
    override fun capabilities(): List<Capability> = if (platform == "web") listOf(Capability.FAST_HIERARCHY) else emptyList()

    override fun deviceInfo(): DeviceInfo = client.guard {
        if (platform == "web") {
            val info = client.webRead("maestro_web_info")
            val fields = listOf("width_pixels", "height_pixels", "width_grid", "height_grid").map { info.path(it) }
            requireAdapter(info.isObject && fields.all { it.isIntegralNumber && it.canConvertToInt() && it.intValue() in 1..16_000 }, "Revyl returned invalid web viewport dimensions.")
            val values = fields.map { it.intValue() }
            requireAdapter(values[0] <= 11_520 && values[1] <= 6_480 && values[2] <= 3_840 && values[3] <= 2_160, "Web viewport exceeds the worker coordinate limits.")
            requireAdapter(values[0].toLong() * values[1] <= 16_000_000, "Web viewport exceeds the pixel limit.")
            val scaleX = values[0].toDouble() / values[2]
            requireAdapter(scaleX in 1.0..3.0 && values[0].toLong() * values[3] == values[1].toLong() * values[2], "Web screenshot and selector viewport scales must agree.")
            return@guard DeviceInfo(Platform.WEB, values[0], values[1], values[2], values[3])
        }
        val png = screenshot()
        val width = ByteBuffer.wrap(png, 16, 4).int
        val height = ByteBuffer.wrap(png, 20, 4).int
        if (platform == "android") return@guard DeviceInfo(Platform.ANDROID, width, height, width, height)
        val hierarchy = NativeHierarchy.parse(client.read("hierarchy"), platform)
        val widthPoints = hierarchy.widthPoints ?: throw AdapterFailure("Missing iOS point viewport.")
        val heightPoints = hierarchy.heightPoints ?: throw AdapterFailure("Missing iOS point viewport.")
        val scaleX = width.toDouble() / widthPoints
        val scaleY = height.toDouble() / heightPoints
        requireAdapter(scaleX in 1.0..4.0 && kotlin.math.abs(scaleX - scaleY) < 0.02, "iOS screenshot pixels do not match the hierarchy point viewport.")
        DeviceInfo(Platform.IOS, width, height, widthPoints, heightPoints)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = client.guard {
        if (platform == "web") return@guard WebHierarchy.parse(client.webRead("maestro_web_hierarchy"))
        if (excludeKeyboardElements) unsupported()
        val native = NativeHierarchy.parse(client.read("hierarchy"), platform).root
        if (!chromeDevToolsEnabled || native.aggregate().none { it.attributes["class"] == "android.webkit.WebView" }) return@guard native
        AndroidWebViewHierarchyClient.mergeHierarchies(native, parseWebViewNodes(client.readState("webview_hierarchy")))
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) = client.guard {
        if (platform == "web") {
            requireAppTarget(appId, platform)
            if (launchArguments.isEmpty()) return@guard client.webAct("navigate", mapOf("url" to appId))
            requireLaunchArguments(launchArguments)
            return@guard client.webAct("maestro_web_launch", mapOf("url" to appId, "arguments" to launchArguments))
        }
        requireAdapter(appIdPattern.matches(appId), "Launch requires a native bundle or package ID.")
        if (launchArguments.isEmpty()) return@guard client.act("launch", mapOf("bundle_id" to appId))
        requireLaunchArguments(launchArguments)
        client.act("launch", mapOf("bundle_id" to appId, "arguments" to launchArguments))
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) = client.guard {
        if (platform == "web") return@guard
        if (permissions.isEmpty()) return@guard
        requireAppId(appId)
        requirePermissions(permissions, platform)
        client.act("set_permissions", mapOf("bundle_id" to appId, "permissions" to permissions))
    }

    override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = client.guard {
        if (enabled) {
            requireAdapter(platform == "android", "DevTools WebView hierarchy is Android-only.")
            client.requireCapability(WorkerCapability.WEBVIEW_HIERARCHY)
        }
        chromeDevToolsEnabled = enabled
    }

    override fun tap(point: Point) {
        if (platform == "web") {
            requireAdapter(point.x in 0..1_000_000 && point.y in 0..1_000_000, "Web tap requires bounded visual-viewport grid or Maestro synthetic coordinates.")
            client.webAct("maestro_web_tap", mapOf("x" to point.x, "y" to point.y))
            return
        }
        val info = deviceInfo()
        requireAdapter(point.x in 0 until info.widthGrid && point.y in 0 until info.heightGrid, "Tap coordinates must be inside the native viewport.")
        client.act("tap", mapOf("x" to point.x, "y" to point.y))
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val png = screenshot()
        val bytes = if (compressed) {
            val image = ImageIO.read(ByteArrayInputStream(png))
            val rgb = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = rgb.createGraphics()
            try { graphics.drawImage(image, 0, 0, null) } finally { graphics.dispose() }
            ByteArrayOutputStream().use { encoded ->
                requireAdapter(ImageIO.write(rgb, "jpeg", encoded), "Could not encode the comparison screenshot.")
                encoded.toByteArray()
            }
        } else png
        out.buffer().use { it.write(bytes) }
    }

    private fun screenshot(): ByteArray = client.guard {
        val bytes = client.read("screenshot")
        requireAdapter(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) &&
            ByteBuffer.wrap(bytes, 8, 4).int == 13 && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR", "Revyl returned an invalid PNG screenshot.")
        val width = ByteBuffer.wrap(bytes, 16, 4).int
        val height = ByteBuffer.wrap(bytes, 20, 4).int
        requireAdapter(width in 1..16_000 && height in 1..16_000 && width.toLong() * height <= 16_000_000, "Screenshot dimensions exceed the pixel limit.")
        var offset = 8
        var chunkCount = 0
        var complete = false
        while (offset < bytes.size) {
            requireAdapter(bytes.size - offset >= 12 && ++chunkCount <= 10_000, "Invalid PNG chunk structure.")
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            requireAdapter(length >= 0 && length <= bytes.size - offset - 12, "Invalid PNG chunk length.")
            val checksum = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
            val expected = ByteBuffer.wrap(bytes, offset + 8 + length, 4).int.toLong() and 0xffffffffL
            requireAdapter(checksum == expected, "PNG checksum validation failed.")
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            requireAdapter(type !in setOf("acTL", "fcTL", "fdAT"), "Animated screenshots are not supported.")
            offset += length + 12
            if (type == "IEND") {
                requireAdapter(length == 0 && offset == bytes.size, "Invalid PNG end marker.")
                complete = true
            }
        }
        requireAdapter(complete, "PNG screenshot is incomplete.")
        try {
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            requireAdapter(image != null && image.width == width && image.height == height, "Revyl returned an invalid PNG screenshot.")
        } catch (_: Exception) {
            throw AdapterFailure("Revyl returned an invalid PNG screenshot.")
        }
        bytes
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        requireAdapter(timeoutMs in 1..1_800_000, "Screen wait must be bounded to 1800000 milliseconds.")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var previous = screenshot()
        while (System.nanoTime() < deadline) {
            Thread.sleep(minOf(200, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
            val current = screenshot()
            if (previous.contentEquals(current)) return true
            previous = current
        }
        return false
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        val deadline = System.nanoTime() + (timeoutMs ?: 1_000).coerceAtMost(120_000) * 1_000_000L
        var previous = contentDescriptor(false)
        while (System.nanoTime() < deadline) {
            Thread.sleep(100)
            val current = contentDescriptor(false)
            if (previous == current) return ViewHierarchy(current)
            previous = current
        }
        return null
    }

    override fun stopApp(appId: String) = client.guard {
        if (platform == "web") return@guard
        requireAppId(appId)
        client.act("kill_app", mapOf("bundle_id" to appId))
    }

    override fun killApp(appId: String) = client.guard {
        if (platform == "web") return@guard
        requireAppId(appId)
        if (platform == "android") client.act("kill_app", mapOf("bundle_id" to appId, "mode" to "process_death")) else stopApp(appId)
    }

    override fun clearAppState(appId: String) = client.guard {
        if (platform == "web") {
            requireAppTarget(appId, platform)
            return@guard client.webAct("maestro_web_clear_state", mapOf("url" to appId))
        }
        requireAppId(appId)
        client.act("clear_app_state", mapOf("bundle_id" to appId))
    }

    override fun clearKeychain() = client.guard {
        if (platform == "ios") client.act("clear_keychain", emptyMap())
    }
    override fun longPress(point: Point) = client.guard {
        if (platform == "web") {
            val info = deviceInfo()
            requireAdapter(point.x in 0 until info.widthGrid && point.y in 0 until info.heightGrid, "Web long press must be inside the viewport.")
            return@guard client.webAct("longpress", mapOf("x" to (point.x.toLong() * info.widthPixels / info.widthGrid).toInt(), "y" to (point.y.toLong() * info.heightPixels / info.heightGrid).toInt(), "duration_ms" to 3_000))
        }
        val info = deviceInfo()
        requireAdapter(point.x in 0 until info.widthGrid && point.y in 0 until info.heightGrid, "Long-press coordinates must be inside the native viewport.")
        client.act("longpress", mapOf("x" to point.x, "y" to point.y, "duration_ms" to 3_000))
    }

    override fun pressKey(code: KeyCode) = client.guard {
        requireSupportedKey(code, platform)
        if (platform == "web") {
            if (code == KeyCode.BACK) return@guard backPress()
            val key = when (code) { KeyCode.ENTER -> "Enter"; KeyCode.BACKSPACE -> "Backspace"; KeyCode.HOME -> "Home"; else -> unsupported() }
            return@guard client.webAct("key", mapOf("key" to key))
        }
        when (code) {
            KeyCode.ENTER, KeyCode.BACKSPACE -> client.act("key", mapOf("key" to code.name))
            KeyCode.HOME -> client.act("go_home", emptyMap())
            KeyCode.BACK -> backPress()
            else -> unsupported()
        }
    }
    override fun scrollVertical() {
        if (platform == "web") {
            client.webAct("maestro_web_scroll", emptyMap())
            return
        }
        val info = deviceInfo()
        swipe(Point(info.widthGrid / 2, info.heightGrid / 2), Point(info.widthGrid / 2, info.heightGrid / 10), if (platform == "ios") 333 else 400)
    }
    override fun isKeyboardVisible(): Boolean = client.guard {
        if (platform == "web") return@guard false
        val visible = client.readState("keyboard").path("visible")
        requireAdapter(visible.isBoolean, "Revyl returned an invalid keyboard state.")
        visible.booleanValue()
    }
    override fun swipe(start: Point, end: Point, durationMs: Long) = client.guard {
        requireSwipeDuration(durationMs)
        val info = deviceInfo()
        requireAdapter(start != end && listOf(start, end).all { it.x in 0 until info.widthGrid && it.y in 0 until info.heightGrid }, "Swipe endpoints must differ and lie inside the native viewport.")
        if (platform == "web") {
            requireAdapter(durationMs <= 5_000, "Web drag duration must not exceed 5000 milliseconds.")
            return@guard client.webAct("drag", mapOf("start_x" to (start.x.toLong() * info.widthPixels / info.widthGrid).toInt(), "start_y" to (start.y.toLong() * info.heightPixels / info.heightGrid).toInt(), "end_x" to (end.x.toLong() * info.widthPixels / info.widthGrid).toInt(), "end_y" to (end.y.toLong() * info.heightPixels / info.heightGrid).toInt(), "duration_ms" to durationMs))
        }
        client.act("drag", mapOf("start_x" to start.x, "start_y" to start.y, "end_x" to end.x, "end_y" to end.y, "duration_ms" to durationMs))
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val start = when (swipeDirection) {
            SwipeDirection.UP -> Point(info.widthGrid / 2, if (platform == "ios") info.heightGrid * 9 / 10 else info.heightGrid / 2)
            SwipeDirection.DOWN -> Point(info.widthGrid / 2, info.heightGrid / 5)
            SwipeDirection.LEFT -> Point(info.widthGrid * 9 / 10, info.heightGrid / 2)
            SwipeDirection.RIGHT -> Point(info.widthGrid / 10, info.heightGrid / 2)
        }
        swipe(start, swipeDirection, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val info = deviceInfo()
        val end = when (direction) {
            SwipeDirection.UP -> Point(elementPoint.x, info.heightGrid / 10)
            SwipeDirection.DOWN -> Point(elementPoint.x, info.heightGrid * 9 / 10)
            SwipeDirection.LEFT -> Point(info.widthGrid / 10, elementPoint.y)
            SwipeDirection.RIGHT -> Point(info.widthGrid * 9 / 10, elementPoint.y)
        }
        swipe(elementPoint, end, durationMs)
    }
    override fun backPress() = client.guard {
        if (platform == "web") return@guard client.webAct("back", emptyMap())
        if (platform != "android") unsupported()
        client.act("back", emptyMap())
    }
    override fun inputText(text: String) = client.guard {
        requireFocusedText(text)
        if (platform == "web") return@guard client.webAct("maestro_web_input", mapOf("text" to text))
        client.inputText(text, platform)
    }
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = client.guard {
        if (platform == "web") {
            requireAppTarget(link, platform)
            return@guard client.webAct("navigate", mapOf("url" to link))
        }
        requirePlainLink(link, autoVerify, browser)
        client.act("open_url", mapOf("url" to link))
    }
    override fun hideKeyboard() = client.guard { if (platform != "web") client.act("hide_keyboard", emptyMap()) }

    override fun startScreenRecording(out: Sink): ScreenRecording = client.guard {
        val recordingId = client.startScreenRecording()
        var timer: ScheduledFuture<*>? = null
        val result = object : ScreenRecording {
            private var stopped = false
            @Synchronized override fun close() {
                if (stopped) return
                stopped = true
                timer?.cancel(false)
                client.guard {
                    try {
                        val video = client.stopScreenRecording(recordingId)
                        out.buffer().use { it.write(video) }
                    } catch (_: IOException) {
                        throw AdapterFailure("Could not write the private recording output; no adapter retry was attempted.")
                    }
                }
            }
        }
        recording = result
        timer = recordingTimer.schedule({ result.close() }, 180, TimeUnit.SECONDS)
        result
    }
    override fun setLocation(latitude: Double, longitude: Double) = client.guard {
        requireLocation(latitude, longitude)
        client.act("set_location", mapOf("latitude" to latitude, "longitude" to longitude))
    }
    override fun setOrientation(orientation: DeviceOrientation) = client.guard {
        val value = when (orientation) {
            DeviceOrientation.PORTRAIT -> "portrait"
            DeviceOrientation.LANDSCAPE_LEFT -> "landscape_left"
            DeviceOrientation.LANDSCAPE_RIGHT -> "landscape_right"
            DeviceOrientation.UPSIDE_DOWN -> "upside_down"
        }
        client.act("set_orientation", mapOf("orientation" to value))
    }
    override fun eraseText(charactersToErase: Int) = client.guard {
        requireAdapter(charactersToErase in 1..MAX_ERASE_CHARACTERS, "Erase count must be an integer from 1 to 100.")
        repeat(charactersToErase) { pressKey(KeyCode.BACKSPACE) }
    }
    override fun setProxy(host: String, port: Int): Nothing = unsupported()
    override fun resetProxy(): Nothing = unsupported()
    override fun addMedia(mediaFiles: List<File>) = client.guard {
        requireAdapter(mediaFiles.isNotEmpty(), "addMedia requires at least one file.")
        mediaFiles.forEach(::requireMediaFile)
        mediaFiles.forEach { file ->
            val bytes = try { file.inputStream().use { it.readNBytes(MAX_MEDIA_FILE_BYTES.toInt() + 1) } } catch (_: IOException) {
                throw AdapterFailure("Could not read a media file; no media request was sent for it.")
            }
            requireAdapter(bytes.size.toLong() in 1..MAX_MEDIA_FILE_BYTES, "Media files must be nonempty regular files of at most 25 MiB.")
            client.act("add_media", mapOf("name" to file.name, "content_base64" to Base64.getEncoder().encodeToString(bytes)))
        }
    }

    override fun isAirplaneModeEnabled(): Boolean = client.guard {
        if (platform != "android") unsupported()
        val enabled = client.readState("airplane_mode").path("enabled")
        requireAdapter(enabled.isBoolean, "Revyl returned an invalid airplane mode state.")
        enabled.booleanValue()
    }

    override fun setAirplaneMode(enabled: Boolean) = client.guard {
        if (platform != "android") unsupported()
        client.act("set_airplane_mode", mapOf("enabled" to enabled))
    }

    override fun isDarkModeEnabled(): Boolean = client.guard {
        when (client.readState("appearance").path("appearance").textValue()) {
            "dark" -> true
            "light" -> false
            else -> throw AdapterFailure("Revyl returned an invalid appearance state.")
        }
    }
    override fun setDarkMode(enabled: Boolean) { client.act("set_appearance", mapOf("appearance" to if (enabled) "dark" else "light")) }
    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> = client.guard {
        if (platform != "web" || query !is OnDeviceElementQuery.Css) unsupported()
        requireAdapter(query.css.toByteArray(Charsets.UTF_8).size in 1..8_192 && query.css.none { it.isISOControl() }, "CSS selectors must be bounded strings without control characters.")
        val response = client.webRead("maestro_web_query", mapOf("css" to query.css))
        requireAdapter(response.isObject && response.path("nodes").isArray, "Revyl returned invalid web selector results.")
        WebHierarchy.parseNodes(response.path("nodes"))
    }
}
