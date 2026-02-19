package io.schemat.connector.fabric.dialog

import io.schemat.connector.core.api.PlayerAdapter
import io.schemat.connector.core.api.dialog.DialogDefinition
import io.schemat.connector.core.api.dialog.DialogService
import net.minecraft.server.MinecraftServer

class FabricDialogService(private val server: MinecraftServer) : DialogService {

    override fun showDialog(player: PlayerAdapter, dialog: DialogDefinition): Boolean {
        val serverPlayer = server.playerManager.getPlayer(player.uuid) ?: return false
        return try {
            FabricDialogRenderer.showDialog(serverPlayer, dialog)
            true
        } catch (e: Exception) {
            false
        }
    }
}
