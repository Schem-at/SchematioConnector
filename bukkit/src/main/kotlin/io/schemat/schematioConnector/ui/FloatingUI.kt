package io.schemat.schematioConnector.ui

import io.schemat.schematioConnector.SchematioConnector
import io.schemat.schematioConnector.ui.components.*
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import org.joml.Matrix4f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Floating UI library for creating interactive 3D interfaces using display entities.
 *
 * FloatingUI provides a system for creating floating 3D user interfaces in Minecraft
 * using display entities (BlockDisplay, TextDisplay). The UI appears in front of the
 * player and can contain interactive elements like buttons, sliders, and text inputs.
 *
 * ## Coordinate System
 *
 * The UI uses a local coordinate system based on the player's horizontal facing direction:
 * - **right**: Positive values move elements to the right from the player's perspective
 * - **up**: Positive values move elements upward (always vertical)
 * - **forward**: Positive values move elements away from the player
 *
 * ## Usage
 *
 * ```kotlin
 * val ui = FloatingUI.create(plugin, player, player.eyeLocation.add(player.location.direction.multiply(3)))
 *
 * // Add a background panel
 * ui.addPanel(0.0, 0.0, 0.1, width = 4f, height = 3f, material = Material.BLACK_CONCRETE)
 *
 * // Add a button
 * ui.addButton(0.0, 0.0, label = "Click Me") {
 *     player.sendMessage("Button clicked!")
 * }
 *
 * // UI will auto-destroy after timeout or when player moves too far
 * ```
 *
 * ## Lifecycle
 *
 * - UI is created via [create] factory method
 * - UI ticks every game tick to update hover states and check timeouts
 * - UI is destroyed when player moves too far, timeout expires, or [destroy] is called
 * - Only one UI per player is allowed (creating a new one destroys the previous)
 *
 * @property plugin The plugin instance
 * @property player The player viewing this UI
 * @property center The center location of the UI in world coordinates
 * @property facing The direction the UI faces (default: player's look direction)
 * @property maxDistance Maximum distance before UI auto-destroys
 * @property timeoutTicks Ticks before UI auto-destroys (default: 1200 = 60 seconds)
 * @property debugMode Enable debug logging for hover detection
 *
 * @see UIElement Base class for all UI elements
 * @see Page Higher-level page system built on FloatingUI
 */
class FloatingUI(
    val plugin: SchematioConnector,
    val player: Player,
    val center: Location,
    private val facing: Vector = player.location.direction.normalize(),
    private val maxDistance: Double = 15.0,
    private val timeoutTicks: Int = 1200, // 60 seconds
    var debugMode: Boolean = false
) {
    
    private val elements = mutableListOf<UIElement>()
    private var ticksAlive = 0
    private var isDestroyed = false
    private var tickTask: BukkitTask? = null

    // UI coordinate system - always perpendicular to horizontal view direction
    // This creates a "flat" UI that doesn't tilt when looking up/down
    val right: Vector
    val up: Vector
    val forward: Vector

    init {
        // Use horizontal direction only (ignore pitch)
        val horizontalFacing = facing.clone().setY(0.0).normalize()

        // Right is perpendicular to horizontal facing
        right = horizontalFacing.clone().crossProduct(Vector(0, 1, 0)).normalize()

        // Up is always vertical
        up = Vector(0, 1, 0)

        // Forward is the horizontal facing direction
        forward = horizontalFacing
    }
    
    // Transformation matrix for UI coordinate system
    val transformMatrix: Matrix4f = calculateTransformMatrix()

    // Callbacks
    var onDestroy: (() -> Unit)? = null

    init {
        startTickTask()
    }
    
    private fun startTickTask() {
        tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (isDestroyed) return@Runnable
            tick()
        }, 1L, 1L)
    }
    
    private fun tick() {
        ticksAlive++

        // Check timeout and distance
        val distance = player.location.toVector().distance(center.toVector())
        if (distance > maxDistance || ticksAlive > timeoutTicks || !player.isOnline) {
            destroy()
            return
        }

        // Update hover states
        updateHoverStates()

        // Update sliders that are being dragged
        elements.filterIsInstance<SliderElement>().forEach { slider ->
            if (slider.isDragging()) {
                slider.update()
            }
        }
    }
    
    private fun updateHoverStates() {
        val eyePos = player.eyeLocation.toVector()
        val lookDir = player.eyeLocation.direction.normalize()
        
        var closestElement: UIElement? = null
        var closestDistance = Double.MAX_VALUE
        
        val shouldLog = debugMode && ticksAlive % 20 == 0
        
        if (shouldLog) {
            plugin.logger.info("[FloatingUI] === Hover Check (tick $ticksAlive) ===")
            plugin.logger.info("[FloatingUI] Eye: ${eyePos.x.format()}, ${eyePos.y.format()}, ${eyePos.z.format()}")
            plugin.logger.info("[FloatingUI] Look dir: ${lookDir.x.format()}, ${lookDir.y.format()}, ${lookDir.z.format()}")
            plugin.logger.info("[FloatingUI] Player yaw: ${player.location.yaw}, pitch: ${player.location.pitch}")
            plugin.logger.info("[FloatingUI] UI center: ${center.x.format()}, ${center.y.format()}, ${center.z.format()}")
        }
        
        // Find closest element being looked at (plane intersection approach)
        for (element in elements) {
            if (!element.isInteractive) continue

            val elementPos = element.getWorldPosition()

            // Check if looking at this element using plane intersection
            val result = if (element.usesRectangularHitbox()) {
                // Use rectangular hitbox detection in UI local space
                isLookingAtRectangularElement(
                    eyePos, lookDir, element.localOffset,
                    element.hitboxWidth, element.hitboxHeight
                )
            } else {
                // Use circular hitbox detection
                isLookingAtElement(eyePos, lookDir, elementPos, element.hitboxSize)
            }

            if (shouldLog) {
                val directDist = eyePos.distance(elementPos)
                if (result != null) {
                    val (dist, offset) = result
                    val hitboxInfo = if (element.usesRectangularHitbox()) {
                        "rect(${element.hitboxWidth.format()}x${element.hitboxHeight.format()})"
                    } else {
                        "circle(r=${element.hitboxSize.format()})"
                    }
                    plugin.logger.info("[FloatingUI]   Element: pos=(${elementPos.x.format()}, ${elementPos.y.format()}, ${elementPos.z.format()}), $hitboxInfo, dist=${dist.format()}, offset=${offset.format()}, HIT!")
                } else {
                    val hitboxInfo = if (element.usesRectangularHitbox()) {
                        "rect(${element.hitboxWidth.format()}x${element.hitboxHeight.format()})"
                    } else {
                        "circle(r=${element.hitboxSize.format()})"
                    }
                    plugin.logger.info("[FloatingUI]   Element: pos=(${elementPos.x.format()}, ${elementPos.y.format()}, ${elementPos.z.format()}), $hitboxInfo, dist=${directDist.format()}, MISS")
                }
            }

            if (result != null) {
                val (dist, _) = result
                // Prefer closer elements
                if (dist < closestDistance) {
                    closestElement = element
                    closestDistance = dist
                }
            }
        }
        
        if (shouldLog) {
            if (closestElement != null) {
                plugin.logger.info("[FloatingUI] >>> HOVERING element at distance: ${closestDistance.format()}")
            } else {
                plugin.logger.info("[FloatingUI] >>> No element being hovered")
            }
        }
        
        // Update hover states
        for (element in elements) {
            val wasHovered = element.isHovered
            element.isHovered = element == closestElement
            
            if (element.isHovered != wasHovered) {
                if (debugMode) {
                    plugin.logger.info("[FloatingUI] !!! Hover CHANGED to: ${element.isHovered}")
                }
                element.onHoverChanged()
            }
        }
    }
    
    private fun Double.format() = String.format("%.2f", this)
    private fun Float.format() = String.format("%.2f", this)

    /**
     * Calculate the transformation matrix for the UI coordinate system.
     * Similar to Panel.calculateTransformMatrix from ScopesAndMore.
     */
    private fun calculateTransformMatrix(): Matrix4f {
        // Create a coordinate system where:
        // - X axis = right vector
        // - Y axis = up vector
        // - Z axis = forward vector
        // - Origin = center location

        return Matrix4f(
            right.x.toFloat(), up.x.toFloat(), forward.x.toFloat(), center.x.toFloat(),
            right.y.toFloat(), up.y.toFloat(), forward.y.toFloat(), center.y.toFloat(),
            right.z.toFloat(), up.z.toFloat(), forward.z.toFloat(), center.z.toFloat(),
            0f, 0f, 0f, 1f
        ).transpose()
    }

    /**
     * Convert local UI coordinates to world coordinates.
     * x and y are in UI space, z is depth (negative = toward player).
     */
    fun localToWorld(x: Double, y: Double, z: Double): Vector {
        val localPos = org.joml.Vector4f(x.toFloat(), y.toFloat(), z.toFloat(), 1f)
        transformMatrix.transform(localPos)
        return Vector(localPos.x.toDouble(), localPos.y.toDouble(), localPos.z.toDouble())
    }

    /**
     * Build a transformation matrix for UI elements (text displays, panels, etc.)
     * that positions and orients them on the UI plane.
     *
     * @param localOffsetX offset in UI right direction
     * @param localOffsetY offset in UI up direction
     * @param localOffsetZ offset in UI forward direction (negative = toward player)
     * @param scaleX width scale
     * @param scaleY height scale
     * @param scaleZ depth scale
     */
    fun buildUIElementMatrix(
        localOffsetX: Float,
        localOffsetY: Float,
        localOffsetZ: Float,
        scaleX: Float,
        scaleY: Float,
        scaleZ: Float = 0.01f
    ): Matrix4f {
        // Build a transformation matrix that positions and orients the element
        // to be aligned with the UI plane (text facing the player)

        // The basis vectors define the orientation:
        // - X axis (right) = ui.right
        // - Y axis (up) = ui.up
        // - Z axis (forward) = -ui.forward (text faces toward player)

        val right = this.right
        val up = this.up
        val forward = this.forward

        // Create rotation matrix from basis vectors
        // We want text to face the player, so Z axis should be -forward
        val rotationMatrix = Matrix4f(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
            -forward.x.toFloat(), -forward.y.toFloat(), -forward.z.toFloat(), 0f,
            0f, 0f, 0f, 1f
        )

        // Scale matrix
        val scaleMatrix = Matrix4f().scaling(scaleX, scaleY, scaleZ)

        // Local offset translation (in UI space, applied before rotation)
        val offsetMatrix = Matrix4f().translation(localOffsetX, localOffsetY, localOffsetZ)

        // Combine: rotation * offset * scale
        return Matrix4f(rotationMatrix).mul(offsetMatrix).mul(scaleMatrix)
    }

    /**
     * Calculate the normal vector of the UI plane (pointing toward player).
     */
    fun getPlaneNormal(): Vector {
        return forward.clone().multiply(-1)
    }
    
    /**
     * Check if ray intersects with a plane (used for UI element hit detection).
     * Based on Panel.rayIntersectsPanel from ScopesAndMore.
     */
    private fun rayIntersectsPlane(
        rayOrigin: Vector,
        rayDirection: Vector,
        planePoint: Vector,
        planeNormal: Vector,
        maxDistance: Double = 15.0
    ): Double? {
        val denominator = planeNormal.dot(rayDirection)

        // Ray is parallel to plane
        if (kotlin.math.abs(denominator) < 0.0001) return null

        val t = planeNormal.dot(planePoint.clone().subtract(rayOrigin)) / denominator

        // Behind player or too far
        if (t < 0 || t > maxDistance) return null

        return t
    }

    /**
     * Check if player is looking at a UI element (circular hitbox).
     * Uses plane intersection to find where the look ray hits the UI plane,
     * then checks if that point is within the element's hitbox.
     */
    private fun isLookingAtElement(
        eye: Vector,
        direction: Vector,
        elementCenter: Vector,
        hitboxSize: Double
    ): Pair<Double, Double>? { // Returns (distance to element, offset from ray)
        // Intersect ray with the UI plane
        val planeNormal = getPlaneNormal()
        val planeDistance = rayIntersectsPlane(eye, direction, center.toVector(), planeNormal)
            ?: return null

        // Where the ray hits the UI plane
        val hitPoint = eye.clone().add(direction.clone().multiply(planeDistance))

        // How far is the hit point from the element center?
        val offset = hitPoint.distance(elementCenter)

        return if (offset < hitboxSize) Pair(planeDistance, offset) else null
    }

    /**
     * Check if player is looking at a rectangular UI element.
     * Uses plane intersection then checks if hit point is within the element's rectangular bounds
     * in UI local coordinate space.
     */
    private fun isLookingAtRectangularElement(
        eye: Vector,
        direction: Vector,
        elementLocalOffset: Vector,
        hitboxWidth: Double,
        hitboxHeight: Double
    ): Pair<Double, Double>? { // Returns (distance to element, offset from center)
        // Intersect ray with the UI plane
        val planeNormal = getPlaneNormal()
        val planeDistance = rayIntersectsPlane(eye, direction, center.toVector(), planeNormal)
            ?: return null

        // Where the ray hits the UI plane
        val hitPoint = eye.clone().add(direction.clone().multiply(planeDistance))

        // Convert hit point to UI local coordinates
        val relativePoint = hitPoint.subtract(center.toVector())
        val localX = relativePoint.dot(right)
        val localY = relativePoint.dot(up)

        // Element bounds in local space (centered on localOffset)
        val halfWidth = hitboxWidth / 2
        val halfHeight = hitboxHeight / 2
        val minX = elementLocalOffset.x - halfWidth
        val maxX = elementLocalOffset.x + halfWidth
        val minY = elementLocalOffset.y - halfHeight
        val maxY = elementLocalOffset.y + halfHeight

        // Check if hit point is within rectangular bounds
        if (localX >= minX && localX <= maxX && localY >= minY && localY <= maxY) {
            // Calculate offset from center for sorting (closer to center = higher priority)
            val offsetX = localX - elementLocalOffset.x
            val offsetY = localY - elementLocalOffset.y
            val offset = kotlin.math.sqrt(offsetX * offsetX + offsetY * offsetY)
            return Pair(planeDistance, offset)
        }

        return null
    }

    /**
     * Get the mouse position on the UI plane in local coordinates.
     * Returns null if the player is not looking at the UI plane.
     */
    fun getMousePositionOnPlane(): Pair<Double, Double>? {
        val eye = player.eyeLocation.toVector()
        val direction = player.eyeLocation.direction.normalize()
        val planeNormal = getPlaneNormal()

        val planeDistance = rayIntersectsPlane(eye, direction, center.toVector(), planeNormal)
            ?: return null

        // Where the ray hits the UI plane
        val hitPoint = eye.clone().add(direction.clone().multiply(planeDistance))

        // Convert to local coordinates
        val relativePoint = hitPoint.subtract(center.toVector())
        val localX = relativePoint.dot(right)
        val localY = relativePoint.dot(up)

        return Pair(localX, localY)
    }
    
    /**
     * Handle click from the global event listener
     */
    fun handleClick(isRightClick: Boolean) {
        if (isDestroyed) return

        if (debugMode) {
            plugin.logger.info("[FloatingUI] === CLICK EVENT ===")
            plugin.logger.info("[FloatingUI] ${if (isRightClick) "Right" else "Left"}-click from ${player.name}")
        }

        // Force update hover state immediately before processing click
        updateHoverStates()

        val hoveredElement = elements.find { it.isHovered && it.isInteractive }
        if (hoveredElement != null) {
            if (debugMode) {
                plugin.logger.info("[FloatingUI] Found hovered element, checking cooldown...")
                plugin.logger.info("[FloatingUI] Can interact: ${hoveredElement.canInteract()}")
            }

            if (hoveredElement.canInteract()) {
                if (debugMode) {
                    plugin.logger.info("[FloatingUI] !!! EXECUTING CLICK HANDLER !!!")
                }
                hoveredElement.markInteracted()
                hoveredElement.onClick?.invoke()
            }
        } else {
            if (debugMode) {
                plugin.logger.info("[FloatingUI] No hovered element found")
                plugin.logger.info("[FloatingUI] Interactive elements: ${elements.count { it.isInteractive }}")
                plugin.logger.info("[FloatingUI] Hovered elements: ${elements.count { it.isHovered }}")
            }
        }
    }
    
    /**
     * Add a button element to the UI
     */
    fun addButton(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        label: String? = null,
        material: Material = Material.STONE,
        hoverMaterial: Material = Material.GOLD_BLOCK,
        size: Float = 0.3f,
        onClick: () -> Unit
    ): ButtonElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = ButtonElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            label = label,
            material = material,
            hoverMaterial = hoverMaterial,
            size = size,
            onClick = onClick
        )
        element.spawn(position)
        elements.add(element)
        
        if (debugMode) {
            plugin.logger.info("[FloatingUI] Added button at world pos: ${position.x}, ${position.y}, ${position.z}")
        }
        
        return element
    }
    
    /**
     * Add a text label (non-interactive)
     */
    fun addLabel(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        text: String,
        scale: Float = 1.0f,
        backgroundColor: Color = Color.fromARGB(0, 0, 0, 0)
    ): LabelElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = LabelElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            text = text,
            scale = scale,
            backgroundColor = backgroundColor
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a label that is aligned to the UI plane (doesn't billboard toward player).
     * This is better for text that should appear "on" the UI surface.
     */
    fun addAlignedLabel(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        text: String,
        scale: Float = 0.4f,
        backgroundColor: Color = Color.fromARGB(0, 0, 0, 0)
    ): AlignedLabelElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = AlignedLabelElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            text = text,
            scale = scale,
            backgroundColor = backgroundColor
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a panel/background element
     */
    fun addPanel(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.1,
        width: Float,
        height: Float,
        material: Material = Material.BLACK_CONCRETE,
        rotateToFace: Boolean = true
    ): PanelElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = PanelElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            width = width,
            height = height,
            material = material,
            rotateToFace = rotateToFace
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add an interactive panel element (rectangular button with hover effect)
     */
    fun addInteractivePanel(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        width: Float,
        height: Float,
        material: Material = Material.BLACK_CONCRETE,
        hoverMaterial: Material? = Material.GRAY_CONCRETE,
        onClick: () -> Unit
    ): InteractivePanelElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = InteractivePanelElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            width = width,
            height = height,
            material = material,
            hoverMaterial = hoverMaterial,
            onClick = onClick
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a grabber element
     */
    fun addGrabber(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        material: Material = Material.LIGHT_BLUE_CONCRETE,
        hoverMaterial: Material = Material.YELLOW_CONCRETE,
        size: Float = 0.15f,
        visible: Boolean = true,
        onClick: () -> Unit
    ): GrabberElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = GrabberElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            material = material,
            hoverMaterial = hoverMaterial,
            size = size,
            initiallyVisible = visible,
            onClick = onClick
        )
        element.spawn(position)
        elements.add(element)
        return element
    }
    
    /**
     * Calculate world position from local UI coordinates.
     * offsetRight: positive = right, negative = left
     * offsetUp: positive = up, negative = down
     * offsetForward: positive = away from player, negative = toward player
     */
    fun calculatePosition(offsetRight: Double, offsetUp: Double, offsetForward: Double): Location {
        // Validate inputs to prevent server crashes from invalid coordinates
        val safeRight = if (offsetRight.isFinite()) offsetRight else 0.0
        val safeUp = if (offsetUp.isFinite()) offsetUp else 0.0
        val safeForward = if (offsetForward.isFinite()) offsetForward else 0.0

        val worldPos = localToWorld(safeRight, safeUp, safeForward)

        // Sanity check: ensure world position is reasonable (within 1000 blocks of center)
        val maxDistance = 1000.0
        if (worldPos.x.isNaN() || worldPos.y.isNaN() || worldPos.z.isNaN() ||
            kotlin.math.abs(worldPos.x - center.x) > maxDistance ||
            kotlin.math.abs(worldPos.y - center.y) > maxDistance ||
            kotlin.math.abs(worldPos.z - center.z) > maxDistance) {
            // Return center position as fallback to prevent crash
            return center.clone()
        }

        return worldPos.toLocation(center.world!!)
    }
    
    fun removeElement(element: UIElement) {
        element.destroy()
        elements.remove(element)
    }
    
    fun getElements(): List<UIElement> = elements.toList()

    /**
     * Add a text input element
     */
    fun addTextInput(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        width: Float = 2.0f,
        height: Float = 0.3f,
        placeholder: String = "Click to edit...",
        value: String = "",
        maxLength: Int = 50,
        onValueChange: (String) -> Unit = {}
    ): TextInputElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = TextInputElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            width = width,
            height = height,
            placeholder = placeholder,
            value = value,
            maxLength = maxLength,
            onValueChange = onValueChange
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a slider element
     */
    fun addSlider(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        width: Float = 2.0f,
        minValue: Float = 0f,
        maxValue: Float = 100f,
        value: Float = 50f,
        step: Float = 1f,
        showValue: Boolean = true,
        label: String? = null,
        onValueChange: (Float) -> Unit = {}
    ): SliderElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = SliderElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            width = width,
            minValue = minValue,
            maxValue = maxValue,
            value = value,
            step = step,
            showValue = showValue,
            label = label,
            onValueChange = onValueChange
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a checkbox element
     */
    fun addCheckbox(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        checked: Boolean = false,
        label: String? = null,
        onToggle: (Boolean) -> Unit = {}
    ): CheckboxElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = CheckboxElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            checked = checked,
            label = label,
            onToggle = onToggle
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a progress bar element
     */
    fun addProgressBar(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        width: Float = 2.0f,
        height: Float = 0.15f,
        progress: Float = 0f,
        showPercentage: Boolean = true,
        label: String? = null,
        barColor: Material = Material.LIME_CONCRETE,
        backgroundColor: Material = Material.GRAY_CONCRETE
    ): ProgressBarElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = ProgressBarElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            width = width,
            height = height,
            progress = progress,
            showPercentage = showPercentage,
            label = label,
            barColor = barColor,
            backgroundColor = backgroundColor
        )
        element.spawn(position)
        elements.add(element)
        return element
    }

    /**
     * Add a tabs element (horizontal tab bar)
     */
    fun addTabs(
        offsetRight: Double,
        offsetUp: Double,
        offsetForward: Double = 0.0,
        tabs: List<TabDefinition>,
        tabWidth: Float = 0.8f,
        tabHeight: Float = 0.3f,
        gap: Float = 0.05f,
        selectedIndex: Int = 0,
        onTabChange: (Int, TabDefinition) -> Unit = { _, _ -> }
    ): TabsElement {
        val position = calculatePosition(offsetRight, offsetUp, offsetForward)
        val element = TabsElement(
            ui = this,
            localOffset = Vector(offsetRight, offsetUp, offsetForward),
            tabs = tabs,
            tabWidth = tabWidth,
            tabHeight = tabHeight,
            gap = gap,
            selectedIndex = selectedIndex,
            onTabChange = onTabChange
        )
        element.spawn(position)
        elements.add(element)

        // Register all tab buttons for hit detection
        element.getTabButtons().forEach { tabButton ->
            elements.add(tabButton)
        }

        return element
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        tickTask?.cancel()

        // Remove all elements
        elements.forEach { it.destroy() }
        elements.clear()

        onDestroy?.invoke()
        activeUIs.remove(player.uniqueId)
    }
    
    fun isDestroyed() = isDestroyed
    
    companion object {
        private val activeUIs = ConcurrentHashMap<UUID, FloatingUI>()
        private var globalListenerRegistered = false
        
        /**
         * Register the global click listener (call once from plugin onEnable)
         */
        fun registerGlobalListener(plugin: SchematioConnector) {
            if (globalListenerRegistered) return
            
            plugin.server.pluginManager.registerEvents(object : Listener {
                @EventHandler(priority = EventPriority.LOW)
                fun onPlayerInteract(event: PlayerInteractEvent) {
                    val ui = activeUIs[event.player.uniqueId] ?: return
                    
                    val isRightClick = event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK
                    val isLeftClick = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
                    
                    if (isRightClick || isLeftClick) {
                        ui.handleClick(isRightClick)
                        if (isRightClick) {
                            event.isCancelled = true // Prevent other actions on right-click
                        }
                    }
                }
            }, plugin)
            
            globalListenerRegistered = true
            plugin.logger.info("[FloatingUI] Global click listener registered")
        }
        
        fun create(
            plugin: SchematioConnector,
            player: Player,
            center: Location,
            facing: Vector = player.location.direction.normalize(),
            debugMode: Boolean = false
        ): FloatingUI {
            // Ensure global listener is registered
            registerGlobalListener(plugin)
            
            activeUIs[player.uniqueId]?.destroy()
            
            val ui = FloatingUI(plugin, player, center, facing, debugMode = debugMode)
            activeUIs[player.uniqueId] = ui
            
            return ui
        }
        
        fun getForPlayer(player: Player): FloatingUI? = activeUIs[player.uniqueId]
        
        fun closeForPlayer(player: Player) {
            activeUIs[player.uniqueId]?.destroy()
        }
        
        fun closeAll() {
            activeUIs.values.toList().forEach { it.destroy() }
        }
        
        fun resetListener() {
            globalListenerRegistered = false
        }
    }
}

/**
 * Base class for UI elements
 */
abstract class UIElement(
    protected val ui: FloatingUI,
    val localOffset: Vector,
    val isInteractive: Boolean = false,
    val hitboxSize: Double = 0.3,  // Legacy circular hitbox (used if hitboxWidth/Height are 0)
    val hitboxWidth: Double = 0.0,  // Rectangular hitbox width (0 = use circular)
    val hitboxHeight: Double = 0.0  // Rectangular hitbox height (0 = use circular)
) {
    var isHovered = false
    var onClick: (() -> Unit)? = null

    private var lastInteractTime = 0L
    private val interactCooldown = 300L

    /** Whether this element uses rectangular hitbox detection */
    fun usesRectangularHitbox(): Boolean = hitboxWidth > 0 && hitboxHeight > 0

    abstract fun spawn(location: Location)
    abstract fun destroy()
    abstract fun onHoverChanged()

    open fun getWorldPosition(): Vector {
        return ui.calculatePosition(localOffset.x, localOffset.y, localOffset.z).toVector()
    }

    fun canInteract(): Boolean {
        return System.currentTimeMillis() - lastInteractTime > interactCooldown
    }

    fun markInteracted() {
        lastInteractTime = System.currentTimeMillis()
    }
}

/**
 * Button element - block display with optional text label
 */
class ButtonElement(
    ui: FloatingUI,
    localOffset: Vector,
    val label: String?,
    val material: Material,
    val hoverMaterial: Material,
    val size: Float,
    onClick: () -> Unit
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = size.toDouble()) { // Hitbox matches visual size

    private var blockDisplay: BlockDisplay? = null
    private var textDisplay: TextDisplay? = null

    init {
        this.onClick = onClick
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        blockDisplay = world.spawn(location, BlockDisplay::class.java) { display ->
            display.block = material.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            // Use UI-aligned transformation matrix for the block
            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -size / 2,
                    localOffsetY = -size / 2,
                    localOffsetZ = -size / 2,
                    scaleX = size,
                    scaleY = size,
                    scaleZ = size
                )
            )
        }

        if (label != null) {
            // Calculate a position in front of the button (toward player)
            val textPosition = ui.calculatePosition(
                localOffset.x,
                localOffset.y,
                localOffset.z - 0.15  // Move toward player (negative = toward player in world space)
            )

            textDisplay = world.spawn(textPosition, TextDisplay::class.java) { display ->
                display.text(Component.text(label))
                display.billboard = Display.Billboard.CENTER  // Face the player
                display.backgroundColor = Color.fromARGB(0, 0, 0, 0)  // Transparent background
                display.isSeeThrough = false  // Don't see through - render on top
                display.brightness = Display.Brightness(15, 15)
                display.alignment = TextDisplay.TextAlignment.CENTER
                display.viewRange = 1.0f

                // Simple scale transformation
                val textScale = 0.35f
                val scaleMatrix = Matrix4f().scaling(textScale, textScale, textScale)
                display.setTransformationMatrix(scaleMatrix)
            }
        }
    }

    override fun destroy() {
        blockDisplay?.remove()
        textDisplay?.remove()
        blockDisplay = null
        textDisplay = null
    }

    override fun onHoverChanged() {
        blockDisplay?.let { display ->
            display.block = if (isHovered) hoverMaterial.createBlockData() else material.createBlockData()
        }
        // Text stays transparent, no background change needed
    }
}

/**
 * Label element - text display only
 */
class LabelElement(
    ui: FloatingUI,
    localOffset: Vector,
    val text: String,
    val scale: Float,
    val backgroundColor: Color
) : UIElement(ui, localOffset, isInteractive = false) {

    private var textDisplay: TextDisplay? = null

    override fun spawn(location: Location) {
        val world = location.world ?: return

        // Calculate a position in front of any background elements (toward player)
        val textPosition = ui.calculatePosition(
            localOffset.x,
            localOffset.y,
            localOffset.z - 0.1  // Move toward player (negative = toward player in world space)
        )

        textDisplay = world.spawn(textPosition, TextDisplay::class.java) { display ->
            display.text(Component.text(text))
            display.billboard = Display.Billboard.CENTER  // Face the player
            display.backgroundColor = backgroundColor
            display.isSeeThrough = false  // Don't see through - render on top
            display.brightness = Display.Brightness(15, 15)
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.viewRange = 1.0f

            // Simple scale transformation
            val scaleMatrix = Matrix4f().scaling(scale, scale, scale)
            display.setTransformationMatrix(scaleMatrix)
        }
    }

    override fun destroy() {
        textDisplay?.remove()
        textDisplay = null
    }

    override fun onHoverChanged() {}

    fun updateText(newText: String) {
        textDisplay?.text(Component.text(newText))
    }
}

/**
 * Aligned label element - text that stays aligned to the UI plane.
 * Uses FIXED billboard mode with UI transformation matrix for proper orientation.
 */
class AlignedLabelElement(
    ui: FloatingUI,
    localOffset: Vector,
    val text: String,
    val scale: Float,
    val backgroundColor: Color
) : UIElement(ui, localOffset, isInteractive = false) {

    private var textDisplay: TextDisplay? = null

    override fun spawn(location: Location) {
        val world = location.world ?: return

        textDisplay = world.spawn(location, TextDisplay::class.java) { display ->
            display.text(Component.text(text))
            display.billboard = Display.Billboard.FIXED
            display.backgroundColor = backgroundColor
            display.isSeeThrough = false  // Don't see through - render on top
            display.brightness = Display.Brightness(15, 15)
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.viewRange = 1.0f

            // Use UI-aligned transformation matrix
            // scaleZ must match scaleX/scaleY for text to render properly
            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = 0f,
                    localOffsetY = 0f,
                    localOffsetZ = 0f,  // No additional offset - position is already calculated
                    scaleX = scale,
                    scaleY = scale,
                    scaleZ = scale  // Must match other scales for text visibility
                )
            )
        }
    }

    override fun destroy() {
        textDisplay?.remove()
        textDisplay = null
    }

    override fun onHoverChanged() {}

    fun updateText(newText: String) {
        textDisplay?.text(Component.text(newText))
    }
}

/**
 * Panel element - flat block display for backgrounds
 */
class PanelElement(
    ui: FloatingUI,
    localOffset: Vector,
    val width: Float,
    val height: Float,
    val material: Material,
    val rotateToFace: Boolean = true
) : UIElement(ui, localOffset, isInteractive = false) {
    
    private var blockDisplay: BlockDisplay? = null
    
    override fun spawn(location: Location) {
        val world = location.world ?: return

        blockDisplay = world.spawn(location, BlockDisplay::class.java) { display ->
            display.block = material.createBlockData()
            display.brightness = Display.Brightness(12, 12)

            // Build transformation matrix in UI local space
            // The panel should face the player (perpendicular to forward vector)
            val matrix = buildPanelMatrix()

            display.setTransformationMatrix(matrix)
        }
    }

    private fun buildPanelMatrix(): Matrix4f {
        // Build a transformation matrix that scales and rotates a unit cube
        // to become a panel with the correct dimensions and orientation
        //
        // The matrix format for JOML is:
        // [ m00 m01 m02 m03 ]   [ right.x  up.x  forward.x  tx ]
        // [ m10 m11 m12 m13 ] = [ right.y  up.y  forward.y  ty ]
        // [ m20 m21 m22 m23 ]   [ right.z  up.z  forward.z  tz ]
        // [ m30 m31 m32 m33 ]   [   0       0        0       1 ]
        //
        // Where columns are the transformed basis vectors

        val right = ui.right
        val up = ui.up
        val forward = ui.forward

        // Build transformation: first translate to center, then apply basis vectors with scale
        val matrix = Matrix4f()

        // For rotateToFace=false, we want the panel to extend in Z with full depth
        val depth = if (rotateToFace) 0.02f else width.coerceAtLeast(height)

        // Start with a translation matrix to center the unit cube
        matrix.translation(-0.5f, -0.5f, -0.5f)

        // Then apply rotation+scale by multiplying with basis matrix
        val basis = Matrix4f(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
            forward.x.toFloat(), forward.y.toFloat(), forward.z.toFloat(), 0f,
            0f, 0f, 0f, 1f
        )

        // Apply scale
        val scale = Matrix4f().scaling(width, height, depth)

        // Combine: result = basis * scale * translate
        return Matrix4f(basis).mul(scale).mul(matrix)
    }
    
    override fun destroy() {
        blockDisplay?.remove()
        blockDisplay = null
    }
    
    override fun onHoverChanged() {}
    
    fun setMaterial(newMaterial: Material) {
        blockDisplay?.block = newMaterial.createBlockData()
    }
}

/**
 * Interactive panel element - flat block display with hover and click support.
 * Used for menu items, buttons with rectangular hitboxes, etc.
 */
class InteractivePanelElement(
    ui: FloatingUI,
    localOffset: Vector,
    val width: Float,
    val height: Float,
    val material: Material,
    val hoverMaterial: Material?,
    onClick: () -> Unit
) : UIElement(
    ui, localOffset,
    isInteractive = true,
    hitboxWidth = width.toDouble(),
    hitboxHeight = height.toDouble()
) {

    private var blockDisplay: BlockDisplay? = null

    init {
        this.onClick = onClick
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        blockDisplay = world.spawn(location, BlockDisplay::class.java) { display ->
            display.block = material.createBlockData()
            display.brightness = Display.Brightness(12, 12)
            display.setTransformationMatrix(buildPanelMatrix())
        }
    }

    private fun buildPanelMatrix(): Matrix4f {
        val right = ui.right
        val up = ui.up
        val forward = ui.forward

        val matrix = Matrix4f()
        val depth = 0.02f

        matrix.translation(-0.5f, -0.5f, -0.5f)

        val basis = Matrix4f(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
            forward.x.toFloat(), forward.y.toFloat(), forward.z.toFloat(), 0f,
            0f, 0f, 0f, 1f
        )

        val scale = Matrix4f().scaling(width, height, depth)

        return Matrix4f(basis).mul(scale).mul(matrix)
    }

    override fun destroy() {
        blockDisplay?.remove()
        blockDisplay = null
    }

    override fun onHoverChanged() {
        if (hoverMaterial != null) {
            blockDisplay?.block = if (isHovered) {
                hoverMaterial.createBlockData()
            } else {
                material.createBlockData()
            }
        }
    }
}

/**
 * Grabber element - small interactive cube that can be shown/hidden
 */
class GrabberElement(
    ui: FloatingUI,
    localOffset: Vector,
    val material: Material,
    val hoverMaterial: Material,
    val size: Float,
    val initiallyVisible: Boolean,
    onClick: () -> Unit
) : UIElement(ui, localOffset, isInteractive = true, hitboxSize = size.toDouble() * 2.0) { // Generous hitbox for grabbers

    private var blockDisplay: BlockDisplay? = null
    var isVisible: Boolean = initiallyVisible
        private set
    var isToggled: Boolean = false
        private set

    init {
        this.onClick = {
            isToggled = !isToggled
            updateAppearance()
            onClick()
        }
    }

    override fun spawn(location: Location) {
        val world = location.world ?: return

        blockDisplay = world.spawn(location, BlockDisplay::class.java) { display ->
            display.block = material.createBlockData()
            display.brightness = Display.Brightness(15, 15)

            // Use UI-aligned transformation matrix
            display.setTransformationMatrix(
                ui.buildUIElementMatrix(
                    localOffsetX = -size / 2,
                    localOffsetY = -size / 2,
                    localOffsetZ = -size / 2,
                    scaleX = size,
                    scaleY = size,
                    scaleZ = size
                )
            )

            display.viewRange = if (initiallyVisible) 1.0f else 0.0f
        }
    }
    
    override fun destroy() {
        blockDisplay?.remove()
        blockDisplay = null
    }
    
    override fun onHoverChanged() {
        if (!isVisible) return
        updateAppearance()
    }
    
    private fun updateAppearance() {
        blockDisplay?.let { display ->
            val mat = when {
                isToggled -> Material.ORANGE_CONCRETE
                isHovered -> hoverMaterial
                else -> material
            }
            display.block = mat.createBlockData()
        }
    }
    
    fun setVisible(visible: Boolean) {
        isVisible = visible
        blockDisplay?.viewRange = if (visible) 1.0f else 0.0f
    }
    
    fun toggle() {
        isToggled = !isToggled
        updateAppearance()
    }

    /**
     * Update the position of this grabber.
     * @param newOffsetRight New offset right from UI center
     * @param newOffsetUp New offset up from UI center
     */
    fun setPosition(newOffsetRight: Double, newOffsetUp: Double) {
        // Update the local offset (we need to access the mutable components)
        localOffset.x = newOffsetRight
        localOffset.y = newOffsetUp

        // Teleport the block display to the new position
        blockDisplay?.let { display ->
            val newLocation = ui.calculatePosition(newOffsetRight, newOffsetUp, localOffset.z)
            display.teleport(newLocation)
        }
    }
}
