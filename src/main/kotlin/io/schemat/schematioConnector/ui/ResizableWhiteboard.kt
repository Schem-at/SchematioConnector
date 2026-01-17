package io.schemat.schematioConnector.ui

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.components.ProgressBarElement
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * A resizable whiteboard UI component with draggable corner handles.
 */
class ResizableWhiteboard(
    private val plugin: SchematioConnector,
    private val ui: FloatingUI,
    private val player: Player,
    initialWidth: Float = 3.0f,
    initialHeight: Float = 2.0f,
    var showDemoComponents: Boolean = false
) {
    var width: Float = initialWidth
        private set
    var height: Float = initialHeight
        private set

    private var resizeModeEnabled = false
    private var activeGrabber: GrabberCorner? = null
    private var updateTask: BukkitTask? = null
    private var tickCount = 0

    // UI elements
    private var whiteboard: PanelElement? = null
    private val frame = mutableListOf<PanelElement>()
    private val grabbers = mutableMapOf<GrabberCorner, GrabberElement>()

    // Demo components (optional)
    private val demoComponents = mutableListOf<UIElement>()
    private var demoProgressBar: ProgressBarElement? = null

    // Grabber corner positions
    enum class GrabberCorner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    private val frameThickness = 0.08f
    private val frameDepth = 0.02f

    init {
        rebuild()
        startUpdateTask()
    }

    private fun rebuild() {
        // Remove existing whiteboard, frame, grabbers, and demo components
        whiteboard?.let { ui.removeElement(it) }
        frame.forEach { ui.removeElement(it) }
        frame.clear()
        grabbers.values.forEach { ui.removeElement(it) }
        grabbers.clear()
        demoComponents.forEach { ui.removeElement(it) }
        demoComponents.clear()

        val halfW = width / 2.0
        val halfH = height / 2.0

        // Add the whiteboard panel (white concrete, at the back of the frame)
        whiteboard = ui.addPanel(
            offsetRight = 0.0,
            offsetUp = 0.0,
            offsetForward = (frameDepth * 2).toDouble(),
            width = width,
            height = height,
            material = Material.WHITE_CONCRETE,
            rotateToFace = true
        )

        // Add frame
        // Top frame
        frame.add(ui.addPanel(
            offsetRight = 0.0,
            offsetUp = halfH + frameThickness / 2,
            offsetForward = frameDepth.toDouble(),
            width = width,
            height = frameThickness,
            material = Material.OBSIDIAN,
            rotateToFace = true
        ))

        // Bottom frame
        frame.add(ui.addPanel(
            offsetRight = 0.0,
            offsetUp = -halfH - frameThickness / 2,
            offsetForward = frameDepth.toDouble(),
            width = width,
            height = frameThickness,
            material = Material.OBSIDIAN,
            rotateToFace = true
        ))

        // Left frame
        frame.add(ui.addPanel(
            offsetRight = -halfW - frameThickness / 2,
            offsetUp = 0.0,
            offsetForward = frameDepth.toDouble(),
            width = frameThickness,
            height = height + frameThickness * 2,
            material = Material.LIME_CONCRETE,
            rotateToFace = true
        ))

        // Right frame
        frame.add(ui.addPanel(
            offsetRight = halfW + frameThickness / 2,
            offsetUp = 0.0,
            offsetForward = frameDepth.toDouble(),
            width = frameThickness,
            height = height + frameThickness * 2,
            material = Material.MAGENTA_CONCRETE,
            rotateToFace = true
        ))

        // Add corner grabbers
        grabbers[GrabberCorner.TOP_LEFT] = ui.addGrabber(
            offsetRight = -halfW,
            offsetUp = halfH,
            offsetForward = -0.05,
            material = Material.LIGHT_BLUE_CONCRETE,
            hoverMaterial = Material.CYAN_CONCRETE,
            size = 0.15f,
            visible = resizeModeEnabled
        ) {
            toggleGrabber(GrabberCorner.TOP_LEFT)
        }

        grabbers[GrabberCorner.TOP_RIGHT] = ui.addGrabber(
            offsetRight = halfW,
            offsetUp = halfH,
            offsetForward = -0.05,
            material = Material.LIGHT_BLUE_CONCRETE,
            hoverMaterial = Material.CYAN_CONCRETE,
            size = 0.15f,
            visible = resizeModeEnabled
        ) {
            toggleGrabber(GrabberCorner.TOP_RIGHT)
        }

        grabbers[GrabberCorner.BOTTOM_LEFT] = ui.addGrabber(
            offsetRight = -halfW,
            offsetUp = -halfH,
            offsetForward = -0.05,
            material = Material.LIGHT_BLUE_CONCRETE,
            hoverMaterial = Material.CYAN_CONCRETE,
            size = 0.15f,
            visible = resizeModeEnabled
        ) {
            toggleGrabber(GrabberCorner.BOTTOM_LEFT)
        }

        grabbers[GrabberCorner.BOTTOM_RIGHT] = ui.addGrabber(
            offsetRight = halfW,
            offsetUp = -halfH,
            offsetForward = -0.05,
            material = Material.LIGHT_BLUE_CONCRETE,
            hoverMaterial = Material.CYAN_CONCRETE,
            size = 0.15f,
            visible = resizeModeEnabled
        ) {
            toggleGrabber(GrabberCorner.BOTTOM_RIGHT)
        }

        // Add demo components if enabled
        if (showDemoComponents) {
            addDemoComponents(halfW, halfH)
        }
    }

    private fun addDemoComponents(halfW: Double, halfH: Double) {
        // Add checkbox
        demoComponents.add(ui.addCheckbox(
            offsetRight = -halfW + 0.5,
            offsetUp = halfH - 0.5,
            offsetForward = -0.05,
            checked = false,
            label = "Enable feature"
        ) { checked ->
            player.sendMessage(Component.text("Checkbox: $checked").color(NamedTextColor.AQUA))
        })

        // Add slider
        demoComponents.add(ui.addSlider(
            offsetRight = 0.0,
            offsetUp = halfH - 0.8,
            offsetForward = -0.05,
            width = width * 0.7f,
            minValue = 0f,
            maxValue = 100f,
            value = 50f,
            step = 5f,
            showValue = true,
            label = "Volume"
        ) { value ->
            player.sendMessage(Component.text("Slider: $value").color(NamedTextColor.AQUA))
        })

        // Add progress bar (store reference for animation)
        demoProgressBar = ui.addProgressBar(
            offsetRight = 0.0,
            offsetUp = halfH - 1.1,
            offsetForward = -0.05,
            width = width * 0.7f,
            height = 0.12f,
            progress = 0.0f,
            showPercentage = true,
            label = "Loading"
        )
        demoComponents.add(demoProgressBar!!)

        // Add text input
        demoComponents.add(ui.addTextInput(
            offsetRight = 0.0,
            offsetUp = halfH - 1.5,
            offsetForward = -0.05,
            width = width * 0.7f,
            height = 0.25f,
            placeholder = "Enter text...",
            value = "",
            maxLength = 30
        ) { value ->
            player.sendMessage(Component.text("Text: $value").color(NamedTextColor.AQUA))
        })
    }

    private fun toggleGrabber(corner: GrabberCorner) {
        if (activeGrabber == corner) {
            // Deactivate
            activeGrabber = null
            grabbers[corner]?.toggle()
            player.sendMessage(Component.text("Released ${corner.name}").color(NamedTextColor.GRAY))
        } else {
            // Deactivate previous grabber if any
            activeGrabber?.let { prev ->
                grabbers[prev]?.toggle()
            }
            // Activate new grabber
            activeGrabber = corner
            grabbers[corner]?.toggle()
            player.sendMessage(Component.text("Grabbed ${corner.name} - Move your mouse to resize").color(NamedTextColor.AQUA))
        }
    }

    fun toggleResizeMode() {
        resizeModeEnabled = !resizeModeEnabled
        grabbers.values.forEach { it.setVisible(resizeModeEnabled) }

        if (!resizeModeEnabled) {
            // Deactivate any active grabber
            activeGrabber?.let { prev ->
                grabbers[prev]?.toggle()
            }
            activeGrabber = null
        }

        val modeText = if (resizeModeEnabled) "§aON" else "§cOFF"
        player.sendMessage(Component.text("Resize mode: $modeText").color(NamedTextColor.YELLOW))
    }

    private fun startUpdateTask() {
        updateTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            update()
        }, 0L, 1L) // Update every tick
    }

    private fun update() {
        tickCount++

        // Animate demo progress bar (cycles every 5 seconds = 100 ticks)
        if (showDemoComponents && demoProgressBar != null) {
            val progress = (tickCount % 100) / 100f
            demoProgressBar?.updateProgress(progress)
        }

        val corner = activeGrabber ?: return

        // Get mouse position on UI plane
        val mousePos = ui.getMousePositionOnPlane() ?: return
        val (mouseX, mouseY) = mousePos

        // Calculate new dimensions based on which corner is being dragged
        val minSize = 0.5f
        val maxSize = 10.0f

        val (newWidth, newHeight) = when (corner) {
            GrabberCorner.TOP_RIGHT -> {
                // Mouse X controls right edge, mouse Y controls top edge
                val w = (mouseX * 2).toFloat().coerceIn(minSize, maxSize)
                val h = (mouseY * 2).toFloat().coerceIn(minSize, maxSize)
                Pair(w, h)
            }
            GrabberCorner.TOP_LEFT -> {
                // Mouse X controls left edge (negative), mouse Y controls top edge
                val w = (-mouseX * 2).toFloat().coerceIn(minSize, maxSize)
                val h = (mouseY * 2).toFloat().coerceIn(minSize, maxSize)
                Pair(w, h)
            }
            GrabberCorner.BOTTOM_RIGHT -> {
                // Mouse X controls right edge, mouse Y controls bottom edge (negative)
                val w = (mouseX * 2).toFloat().coerceIn(minSize, maxSize)
                val h = (-mouseY * 2).toFloat().coerceIn(minSize, maxSize)
                Pair(w, h)
            }
            GrabberCorner.BOTTOM_LEFT -> {
                // Mouse X controls left edge (negative), mouse Y controls bottom edge (negative)
                val w = (-mouseX * 2).toFloat().coerceIn(minSize, maxSize)
                val h = (-mouseY * 2).toFloat().coerceIn(minSize, maxSize)
                Pair(w, h)
            }
        }

        // Only rebuild if dimensions changed significantly
        if (kotlin.math.abs(newWidth - width) > 0.1f || kotlin.math.abs(newHeight - height) > 0.1f) {
            width = newWidth
            height = newHeight
            rebuild()
        }
    }

    fun destroy() {
        updateTask?.cancel()
        // Elements will be destroyed by the UI itself
    }
}
