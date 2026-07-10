package io.schemat.connector.fabric.client.render.data

import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState

/**
 * Resolves a canonical Minecraft blockstate string (as produced by
 * [SchematicData.canonicalState], e.g. `minecraft:oak_log[axis=x]`) into a real
 * [BlockState] using the vanilla blockstate parser against this client's block
 * registry.
 *
 * Because it goes through the live registry, ANY block the client has registered
 * resolves — including modded blocks — which is what lets the native preview render
 * modded builds. A string whose namespace/block isn't registered (a mod the viewer
 * doesn't have, or a malformed property) resolves to `null` and is counted in
 * [unresolvedCount] so callers can show a "N block types hidden" note.
 *
 * Results are memoized by string. Populate on the client thread (snapshot build);
 * the cache is not synchronized.
 */
object BlockStateMapper {
    private val cache = HashMap<String, BlockState?>()
    private val unresolved = HashSet<String>()

    /** Number of DISTINCT state strings that failed to resolve this session. */
    val unresolvedCount: Int get() = unresolved.size

    /**
     * Parses [stateString] to a [BlockState], or `null` if the block isn't
     * registered in this client or the string is malformed. Memoized by string.
     */
    fun parse(stateString: String): BlockState? {
        cache[stateString]?.let { return it }
        if (cache.containsKey(stateString)) return null // cached negative result

        val state = runCatching {
            // Registry<Block> extends HolderLookup.RegistryLookup<Block>, so the
            // registry can be passed directly as the block lookup.
            BlockStateParser
                .parseForBlock(BuiltInRegistries.BLOCK, stateString, false)
                .blockState()
        }.getOrNull()

        cache[stateString] = state
        if (state == null) unresolved.add(stateString)
        return state
    }

    /** Test/diagnostic hook: drop all cached results and the unresolved tally. */
    fun clearCache() {
        cache.clear()
        unresolved.clear()
    }
}
