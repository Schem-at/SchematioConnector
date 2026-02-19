package io.schemat.connector.core.api.dialog

import io.schemat.connector.core.api.PlayerAdapter

interface DialogService {
    fun showDialog(player: PlayerAdapter, dialog: DialogDefinition): Boolean
}
