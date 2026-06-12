package io.schemat.connector.fabric.client.render

import kotlin.math.tan

enum class Projection { ISOMETRIC, PERSPECTIVE }

/**
 * Background treatment, mirroring the website renderer's options:
 * - [STUDIO]: solid #7ea8ff for ISOMETRIC, HDRI day-sky for PERSPECTIVE
 *   (the original behavior).
 * - [TRANSPARENT]: no backdrop at all - the framebuffer is cleared to alpha 0
 *   and the capture preserves alpha, yielding a build-only PNG with real
 *   transparency (the website's transparent PNG export).
 */
enum class BackgroundMode { STUDIO, TRANSPARENT }

/**
 * Camera framing around the schematic's center. yaw/pitch in degrees;
 * [distance] is a MULTIPLIER of the build's bounding radius (the renderer's
 * actual camera distance is `distance * radius`, and its ortho half-extent is
 * `ORTHO_EXTENT_FACTOR * distance * radius`) - which makes auto-fit
 * radius-independent: [fitDistance] is a pure function of the projection.
 */
data class CameraPose(
    val yaw: Float = 45f,
    val pitch: Float = 30f,
    val distance: Float = 1.6f,   // multiplier of the build's bounding radius
    val projection: Projection = Projection.ISOMETRIC,
    /**
     * Screen-plane pan offset along the camera's right/up axes, in
     * RADIUS-MULTIPLES (the renderer translates by `pan * radius` in view
     * space), so the offset scales with build size exactly like [distance].
     * 0/0 = build centered. Matches the website's OrbitControls right-drag.
     */
    val panX: Float = 0f,
    val panY: Float = 0f,
    /**
     * Vertical perspective FOV in degrees (ignored by ISOMETRIC). Default is
     * the shared [PERSPECTIVE_FOV_DEG]; the composer offers a small cycler
     * (35/45/55/70°). Part of the data class, so pose-dirty caching re-renders
     * on FOV change, and all fit/pan math below derives from THIS value so
     * auto-fit stays correct at any FOV.
     */
    val fovDeg: Float = PERSPECTIVE_FOV_DEG.toFloat(),
) {
    companion object {
        /** Zoom clamp, as multiples of the bounding radius. */
        const val MIN_DISTANCE = 0.6f
        const val MAX_DISTANCE = 6f

        /** Pan clamp (radius-multiples) - keeps the build from getting lost offscreen. */
        const val MAX_PAN = 4f

        /**
         * Vertical view half-extent at the build's center plane, as a
         * multiple of `distance * radius` - the shared quantity behind BOTH
         * [fitDistance] and [pan]'s cursor-1:1 scaling.
         */
        private fun viewHalfExtentFactor(projection: Projection, fovDeg: Float): Float = when (projection) {
            Projection.ISOMETRIC -> ORTHO_EXTENT_FACTOR
            Projection.PERSPECTIVE -> tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        }

        /** Auto-fit margin: the bounding sphere fills ~1/this of the frame height (~10% padding). */
        private const val FIT_PADDING = 1.1f

        /**
         * The [distance] multiplier at which the build's bounding sphere
         * exactly fills the (vertical) frame with [FIT_PADDING] margin.
         * 16:9 is wider than tall, so the vertical extent is the binding
         * constraint; the bounding sphere guarantees a fit at ANY yaw/pitch.
         *
         * Derivation against [OffscreenSchematicRenderer]'s projection math:
         * - ISOMETRIC: vertical ortho half-extent = ORTHO_EXTENT_FACTOR ·
         *   distance · radius; we need it = radius · FIT_PADDING, so
         *   distance = FIT_PADDING / ORTHO_EXTENT_FACTOR (≈ 1.47).
         * - PERSPECTIVE: half-height at the center plane = camDist ·
         *   tan(fov/2) with camDist = distance · radius; we need it =
         *   radius · FIT_PADDING, so distance = FIT_PADDING / tan(fov/2)
         *   (≈ 2.11 at 55°).
         */
        fun fitDistance(projection: Projection, fovDeg: Float = PERSPECTIVE_FOV_DEG.toFloat()): Float =
            (FIT_PADDING / viewHalfExtentFactor(projection, fovDeg)).coerceIn(MIN_DISTANCE, MAX_DISTANCE)

        // Presets ship pre-fitted so a fresh pose is always well-framed.
        //
        // Compass naming derives from the renderer's eye position
        // eye = center + (-sin(yaw)·h, ·, -cos(yaw)·h): yaw 0 puts the camera
        // NORTH of the build (-Z) looking south, yaw 45 puts it at the
        // NORTH-WEST corner, and yaw increases counter-clockwise on the
        // compass (45→NW, 135→SW, 225→SE, 315→NE).
        private fun isoCorner(yaw: Float) =
            CameraPose(yaw, 30f, fitDistance(Projection.ISOMETRIC), Projection.ISOMETRIC)

        private fun perspectiveFace(yaw: Float) =
            CameraPose(yaw, 5f, fitDistance(Projection.PERSPECTIVE), Projection.PERSPECTIVE)

        // Isometric corner presets (website parity: NW/NE/SW/SE @ pitch 30).
        val ISO_NW = isoCorner(45f)
        val ISO_SW = isoCorner(135f)
        val ISO_SE = isoCorner(225f)
        val ISO_NE = isoCorner(315f)

        /** The classic three-quarter view - kept as the open/reset default (same pose as [ISO_NW]). */
        val ISO = ISO_NW

        // Perspective face presets (website parity: faces + top-down).
        val FRONT = perspectiveFace(0f)
        val BACK = perspectiveFace(180f)
        val LEFT = perspectiveFace(90f)
        val RIGHT = perspectiveFace(270f)
        val TOP = CameraPose(0f, 89f, fitDistance(Projection.ISOMETRIC), Projection.ISOMETRIC)
    }
    fun orbit(dYaw: Float, dPitch: Float) = copy(yaw = yaw + dYaw, pitch = (pitch + dPitch).coerceIn(-89f, 89f))
    fun zoom(factor: Float) = copy(distance = (distance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE))

    /**
     * Screen-plane pan (website OrbitControls right-drag). [dxFrac]/[dyFrac]
     * are the cursor delta as a FRACTION OF THE PREVIEW HEIGHT, in screen
     * coordinates (right/down positive - the y flip to view-space "up" happens
     * here). The visible frame height at the build's center plane is
     * `2 · viewHalfExtentFactor · distance` (radius-multiples), so scaling by
     * that makes the build track the cursor 1:1 at any zoom, projection,
     * build size, or GUI scale.
     */
    fun pan(dxFrac: Float, dyFrac: Float): CameraPose {
        val frameHeight = 2f * viewHalfExtentFactor(projection, fovDeg) * distance
        return copy(
            panX = (panX + dxFrac * frameHeight).coerceIn(-MAX_PAN, MAX_PAN),
            panY = (panY - dyFrac * frameHeight).coerceIn(-MAX_PAN, MAX_PAN),
        )
    }

    /** Re-frame: keep yaw/pitch/projection/FOV, snap [distance] back to the auto-fit value and recenter (pan → 0). */
    fun fit() = copy(distance = fitDistance(projection, fovDeg), panX = 0f, panY = 0f)
}

/** Result of a capture. */
sealed class CaptureResult {
    data class Success(val pngBytes: ByteArray) : CaptureResult()
    data class Failure(val message: String) : CaptureResult()
}

// 16:9 render target matching the website's preview crop (720x405 / 360x202,
// aspect-video cards) - capturing at the site's aspect means the server-side
// center-crop is a no-op. API caps preview uploads at 5MB.
const val CAPTURE_WIDTH = 1280
const val CAPTURE_HEIGHT = 720
const val CAPTURE_ASPECT = CAPTURE_WIDTH.toFloat() / CAPTURE_HEIGHT.toFloat() // 16:9
const val VOLUME_CAP = 96               // max bounding dimension rendered without down-sampling

/**
 * Shared camera-framing constants - single source of truth for BOTH the
 * renderer's projection matrices and [CameraPose.fitDistance], so auto-fit and
 * the actual render frame can never drift apart.
 */
/** ISOMETRIC vertical ortho half-extent = this · pose.distance · radius. */
const val ORTHO_EXTENT_FACTOR = 0.75f

/** PERSPECTIVE vertical field of view, degrees - the DEFAULT for [CameraPose.fovDeg]. */
const val PERSPECTIVE_FOV_DEG = 55.0

/** FOV choices offered by the composer's cycler (website parity; default 55°). */
val FOV_OPTIONS_DEG = floatArrayOf(35f, 45f, 55f, 70f)
