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
import okio.Sink
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

class RevylDriver(private val client: RevylClient, private val platform: String) : Driver {
    private var closed = false

    override fun name(): String = "Revyl (native subset)"
    override fun open() { requireAdapter(!closed, "The local Revyl driver is closed.") }
    override fun close() { closed = true; client.close() }
    override fun isShutdown(): Boolean = closed
    override fun capabilities(): List<Capability> = emptyList()

    override fun deviceInfo(): DeviceInfo = client.guard {
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
        if (excludeKeyboardElements) unsupported()
        NativeHierarchy.parse(client.read("hierarchy"), platform).root
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        requireAdapter(appIdPattern.matches(appId), "Launch requires a native bundle or package ID.")
        if (launchArguments.isNotEmpty()) unsupported()
        client.act("launch", mapOf("bundle_id" to appId))
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        if (permissions.isNotEmpty()) unsupported()
    }

    override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) {
        if (enabled) unsupported()
    }

    override fun tap(point: Point) {
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
        requireAdapter(timeoutMs in 1..120_000, "Screen wait must be bounded to 120000 milliseconds.")
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

    override fun stopApp(appId: String): Nothing = unsupported()
    override fun killApp(appId: String): Nothing = unsupported()
    override fun clearAppState(appId: String): Nothing = unsupported()
    override fun clearKeychain(): Nothing = unsupported()
    override fun longPress(point: Point) = client.guard {
        val info = deviceInfo()
        requireAdapter(point.x in 0 until info.widthGrid && point.y in 0 until info.heightGrid, "Long-press coordinates must be inside the native viewport.")
        client.act("longpress", mapOf("x" to point.x, "y" to point.y, "duration_ms" to 3_000))
    }

    override fun pressKey(code: KeyCode) = client.guard {
        requireSupportedKey(code, platform)
        when (code) {
            KeyCode.ENTER, KeyCode.BACKSPACE -> client.act("key", mapOf("key" to code.name))
            KeyCode.HOME -> client.act("go_home", emptyMap())
            KeyCode.BACK -> backPress()
            else -> unsupported()
        }
    }
    override fun scrollVertical() {
        val info = deviceInfo()
        swipe(Point(info.widthGrid / 2, info.heightGrid / 2), Point(info.widthGrid / 2, info.heightGrid / 10), if (platform == "ios") 333 else 400)
    }
    override fun isKeyboardVisible(): Nothing = unsupported()
    override fun swipe(start: Point, end: Point, durationMs: Long) = client.guard {
        requireSwipeDuration(durationMs)
        val info = deviceInfo()
        requireAdapter(start != end && listOf(start, end).all { it.x in 0 until info.widthGrid && it.y in 0 until info.heightGrid }, "Swipe endpoints must differ and lie inside the native viewport.")
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
        if (platform != "android") unsupported()
        client.act("back", emptyMap())
    }
    override fun inputText(text: String) = client.guard {
        if (platform != "android") unsupported()
        requireFocusedText(text)
        client.act("text-input", mapOf("text" to text))
    }
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = client.guard {
        requirePlainLink(link, autoVerify, browser)
        client.act("open_url", mapOf("url" to link))
    }
    override fun hideKeyboard(): Nothing = unsupported()
    override fun startScreenRecording(out: Sink): ScreenRecording = unsupported()
    override fun setLocation(latitude: Double, longitude: Double) = client.guard {
        requireLocation(latitude, longitude)
        client.act("set_location", mapOf("latitude" to latitude, "longitude" to longitude))
    }
    override fun setOrientation(orientation: DeviceOrientation): Nothing = unsupported()
    override fun eraseText(charactersToErase: Int) = client.guard {
        requireAdapter(charactersToErase in 1..MAX_ERASE_CHARACTERS, "Erase count must be an integer from 1 to 100.")
        repeat(charactersToErase) { pressKey(KeyCode.BACKSPACE) }
    }
    override fun setProxy(host: String, port: Int): Nothing = unsupported()
    override fun resetProxy(): Nothing = unsupported()
    override fun addMedia(mediaFiles: List<File>): Nothing = unsupported()
    override fun isAirplaneModeEnabled(): Boolean = unsupported()
    override fun setAirplaneMode(enabled: Boolean): Nothing = unsupported()
    override fun isDarkModeEnabled(): Boolean = unsupported()
    override fun setDarkMode(enabled: Boolean) { client.act("set_appearance", mapOf("appearance" to if (enabled) "dark" else "light")) }
    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> = unsupported()
}
