package io.schemat.connector.core.vcs

/** What the session is for: read-only viewing or commit-conflict resolution (spec §3). */
enum class SessionMode { VIEW, RESOLVE }

/** Per-region resolution choice (RESOLVE mode). */
enum class Choice { MINE, THEIRS }

/**
 * Where the diff is rendered in the world: the player's position (or WE selection /
 * edit site) at session start. [rotationDegrees] reserved for rotation-aware anchoring
 * (out of scope in B1 beyond what the paste applied); [world] lets the platform layer
 * detect world changes.
 */
data class Anchor(
    val x: Int,
    val y: Int,
    val z: Int,
    val rotationDegrees: Int = 0,
    val world: String? = null,
)

/**
 * Per-player diff session state (spec §3). Pure Kotlin - platform layers own rendering
 * and lifecycle triggers (quit/world change/idle) and call in here.
 *
 * One session per player; opening a new one must [dispose] the old (enforced by the
 * platform session manager). All mutating members throw once disposed.
 *
 * @param labels display names of the two sides: first = base/THEIRS side, second =
 *   other/MINE side (in RESOLVE: new HEAD vs the player's edit)
 * @param clock injectable millisecond clock for idle tracking
 */
class DiffSession(
    val schematicId: String,
    val labels: Pair<String, String>,
    val anchor: Anchor,
    val regions: List<DiffRegion>,
    val mode: SessionMode,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Index of the focused region (0-based); 0 even when [regions] is empty. */
    var cursor: Int = 0
        private set

    private val choicesByRegion = LinkedHashMap<Int, Choice>()

    /** Per-region resolution choices keyed by [DiffRegion.id]. */
    val choices: Map<Int, Choice> get() = choicesByRegion

    private val hiddenLayers = mutableSetOf<DiffKind>()

    var disposed: Boolean = false
        private set

    private var lastInteractionAt: Long = clock()

    private fun checkAlive() = check(!disposed) { "Diff session is disposed" }

    private fun touch() {
        lastInteractionAt = clock()
    }

    /** Milliseconds since the last interaction (for the 10-min idle disposal). */
    fun idleMs(): Long = clock() - lastInteractionAt

    /** The focused region, or null when the diff has none. */
    val focusedRegion: DiffRegion? get() = regions.getOrNull(cursor)

    /** Advances focus to the next region, wrapping. */
    fun next() {
        checkAlive()
        touch()
        if (regions.isEmpty()) return
        cursor = (cursor + 1) % regions.size
    }

    /** Moves focus to the previous region, wrapping. */
    fun prev() {
        checkAlive()
        touch()
        if (regions.isEmpty()) return
        cursor = (cursor - 1 + regions.size) % regions.size
    }

    /** Focuses region [index] (0-based); false when out of range. */
    fun goto(index: Int): Boolean {
        checkAlive()
        touch()
        if (index !in regions.indices) return false
        cursor = index
        return true
    }

    fun isLayerVisible(kind: DiffKind): Boolean = kind !in hiddenLayers

    fun setLayerVisible(kind: DiffKind, visible: Boolean) {
        checkAlive()
        touch()
        if (visible) hiddenLayers.remove(kind) else hiddenLayers.add(kind)
    }

    /**
     * Records [choice] for the focused region. RESOLVE mode only; re-choosing
     * overwrites. Throws when the session is a VIEW session or has no regions.
     */
    fun choose(choice: Choice) {
        checkAlive()
        touch()
        check(mode == SessionMode.RESOLVE) { "Choices are only available in RESOLVE mode" }
        val region = focusedRegion ?: throw IllegalStateException("No region to choose for")
        choicesByRegion[region.id] = choice
    }

    /** True when every region has a choice (`done` gate; trivially true with none). */
    val allDecided: Boolean get() = regions.all { choicesByRegion.containsKey(it.id) }

    /** Ids of regions still without a choice (for the `done` chat summary). */
    val undecidedRegionIds: List<Int> get() = regions.filter { it.id !in choicesByRegion }.map { it.id }

    /** Idempotent; the session refuses further mutation afterwards. */
    fun dispose() {
        disposed = true
    }
}
