package io.schemat.connector.fabric.worldedit

import com.sk89q.worldedit.entity.Player
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/**
 * Adapts a [ServerPlayer] to a WorldEdit [Player] through
 * `com.sk89q.worldedit.fabric.FabricAdapter`, by reflection: worldedit-fabric is not on
 * the compile classpath (Loom mapping mismatch; see fabric/build.gradle.kts), but its
 * classes are present at runtime whenever the `worldedit` mod is loaded.
 *
 * WorldEdit 7.3 exposes a static `adaptPlayer(ServerPlayer)`; 7.4 replaced it with the
 * shared `FabricAdapter.get().fromNativePlayer(ServerPlayer)`. Both are supported.
 */
object WorldEditPlayerAdapter {

    private const val ADAPTER_CLASS = "com.sk89q.worldedit.fabric.FabricAdapter"
    private val LOGGER = LoggerFactory.getLogger("schematioconnector-worldedit")

    fun adapt(player: ServerPlayer): Player? {
        return try {
            val adapt = resolve(Class.forName(ADAPTER_CLASS))
            if (adapt == null) {
                LOGGER.warn("Unsupported WorldEdit FabricAdapter: no adaptPlayer or fromNativePlayer")
                return null
            }
            adapt(player) as Player
        } catch (t: Throwable) {
            LOGGER.warn("Could not adapt {} to a WorldEdit player", player.scoreboardName, t)
            null
        }
    }

    /**
     * Native-player -> WorldEdit-player function for whichever API [adapterClass]
     * offers, or null when it offers neither.
     */
    internal fun resolve(adapterClass: Class<*>): ((Any) -> Any?)? {
        val methods = adapterClass.methods
        methods.firstOrNull { it.name == "adaptPlayer" && it.parameterCount == 1 && Modifier.isStatic(it.modifiers) }
            ?.let { legacy -> return { player -> legacy.invoke(null, player) } }
        val get = methods.firstOrNull { it.name == "get" && it.parameterCount == 0 && Modifier.isStatic(it.modifiers) }
            ?: return null
        val fromNative = methods.firstOrNull { it.name == "fromNativePlayer" && it.parameterCount == 1 }
            ?: return null
        return { player -> fromNative.invoke(get.invoke(null), player) }
    }
}
