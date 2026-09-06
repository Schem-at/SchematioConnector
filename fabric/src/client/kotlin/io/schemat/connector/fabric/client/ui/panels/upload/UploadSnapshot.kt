package io.schemat.connector.fabric.client.ui.panels.upload

import io.schemat.connector.fabric.client.integration.Bridges
import io.schemat.connector.fabric.client.integration.SourceKind
import io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel
import io.schemat.connector.fabric.client.ui.widgets.Widgets
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/** Capture once; every preview and submission in this wizard uses these bytes. */
internal fun UploadWizardPanel.captureSource(onReady: (ByteArray) -> Unit) {
    frozenBytes?.let { onReady(it); return }
    val source = selectedSource ?: return
    val epoch = snapshotEpoch
    val world = Minecraft.getInstance().level
    exporting = true
    val complete: (ByteArray?, String?) -> Unit = { bytes, error ->
        services.onMainThread {
            if (snapshotEpoch == epoch && selectedSource == source) {
                exporting = false
                val worldChanged = source.kind != SourceKind.LOCAL_FILE && Minecraft.getInstance().level !== world
                if (bytes == null || bytes.size > MAX_UPLOAD_SNAPSHOT || worldChanged) {
                    statusMessage = when {
                        worldChanged -> "World changed. Capture the source again."
                        bytes != null -> "This build exceeds the 64 MiB upload limit."
                        else -> error ?: "Could not capture this source"
                    }
                    statusKind = Widgets.StatusKind.DANGER
                } else {
                    frozenBytes = bytes
                    onReady(bytes)
                }
            }
        }
    }
    when (source.kind) {
        SourceKind.LOCAL_FILE -> snapshotJob = services.scope.launch {
            try {
                val bytes = Files.newInputStream(Path.of(source.id)).use { it.readNBytes(MAX_UPLOAD_SNAPSHOT + 1) }
                complete(bytes, null)
            } catch (e: Exception) { complete(null, e.message) }
        }
        SourceKind.WORLDEDIT_CLIPBOARD -> Bridges.worldEdit.clipboardToBytes(complete)
        SourceKind.PLACEMENT, SourceKind.AREA_SELECTION -> Bridges.litematica.exportToBytes(source, complete)
    }
}

private const val MAX_UPLOAD_SNAPSHOT = 64 * 1024 * 1024
