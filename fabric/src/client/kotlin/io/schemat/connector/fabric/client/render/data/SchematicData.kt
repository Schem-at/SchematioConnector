package io.schemat.connector.fabric.client.render.data

import com.github.schemat.nucleation.Schematic

/**
 * Kotlin facade over Nucleation's [Schematic]. Parses schematic bytes (any format
 * Nucleation auto-detects — litematic / schem / nbt / mcstructure / snapshot) and
 * iterates the stored blocks.
 *
 * Nucleation is used *only* as a data source: meshing, model lookup, and rendering
 * are the Minecraft client's responsibility (which is what unlocks modded-block
 * rendering — the client already knows how to render anything it has registered).
 *
 * Holds a native handle, so it is [AutoCloseable]: always use it via `use { }` or
 * call [close] to release the underlying Rust schematic.
 *
 * Limitation (Nucleation 0.2.13): the JNI surface exposes no block-entity NBT, so
 * block entities (chests, signs, banners…) render with their default state. Revisit
 * if/when Nucleation surfaces per-block NBT.
 */
class SchematicData private constructor(private val handle: Schematic) : AutoCloseable {
    private val dims = handle.dimensions()

    /** Width along +X. */
    val sizeX: Int get() = dims.width()

    /** Height along +Y. */
    val sizeY: Int get() = dims.height()

    /** Length along +Z. */
    val sizeZ: Int get() = dims.length()

    /** Count of stored (non-air) blocks, per Nucleation. */
    val blockCount: Int get() = handle.blockCount()

    /**
     * Visits every stored block, passing its position and a canonical Minecraft
     * blockstate string (`minecraft:name` or `minecraft:name[k=v,…]`, properties
     * sorted by key for determinism) ready for the vanilla blockstate parser.
     */
    fun forEachBlock(action: (x: Int, y: Int, z: Int, stateString: String) -> Unit) {
        for (b in handle) {
            action(b.x(), b.y(), b.z(), canonicalState(b.name(), b.properties()))
        }
    }

    override fun close() = handle.close()

    companion object {
        /** Parses schematic bytes; Nucleation auto-detects the container format. */
        fun fromBytes(bytes: ByteArray): SchematicData = SchematicData(Schematic.fromBytes(bytes))

        /**
         * Builds the canonical `name[k=v,…]` blockstate string. Properties are sorted
         * by key so the result is stable regardless of the source map's ordering.
         */
        fun canonicalState(name: String, props: Map<String, String>): String =
            if (props.isEmpty()) {
                name
            } else {
                props.entries
                    .sortedBy { it.key }
                    .joinToString(separator = ",", prefix = "$name[", postfix = "]") { "${it.key}=${it.value}" }
            }
    }
}
