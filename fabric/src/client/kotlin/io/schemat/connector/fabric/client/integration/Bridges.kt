package io.schemat.connector.fabric.client.integration

import io.schemat.connector.fabric.client.render.SchematicRenderSource
import java.io.File
import java.nio.file.Path

/**
 * Seam between the client UI and optional mod integrations (Litematica / WorldEdit).
 *
 * The UI only ever talks to these interfaces; real implementations are installed into
 * [Bridges] by Plan 4 when the respective mod is detected. The Noop defaults keep every
 * feature compiling and visibly disabled when the mod is absent.
 */
interface LitematicaBridge {
    val isAvailable: Boolean

    /** Load [file] as a Litematica schematic (and create a placement); result on render thread. */
    fun loadSchematic(file: File, name: String, onResult: (Boolean, String?) -> Unit)

    /** Export candidates: loaded placements + area selections. */
    fun listExportSources(): List<ExportSource>

    /**
     * What the user currently has "in hand": the selected schematic placement when one
     * is selected, else the current area selection (when it has regions), else null.
     * Lets the upload wizard skip its source step. Client thread only.
     */
    fun currentSelectionSource(): ExportSource?

    /** Serialize [source] to `.litematic` bytes; result on render thread. */
    fun exportToBytes(source: ExportSource, onResult: (ByteArray?, String?) -> Unit)

    /** Litematica's configured schematics directory, or null when unavailable. */
    fun schematicsDirectory(): Path?

    /**
     * Build a [SchematicRenderSource] for the thumbnail composer from [source].
     * Placements use the live schematic world; area selections use the client world;
     * local files are loaded into a Litematica placement first. Result on render thread.
     */
    fun loadRenderSource(source: ExportSource, onResult: (SchematicRenderSource?, String?) -> Unit)
}

/** One selectable thing that can be exported/uploaded. */
data class ExportSource(val id: String, val label: String, val kind: SourceKind)

enum class SourceKind { PLACEMENT, AREA_SELECTION, WORLDEDIT_CLIPBOARD, LOCAL_FILE }

interface WorldEditBridge {
    val isAvailable: Boolean

    /** Serialize the current clipboard to `.schem` bytes; result on render thread. */
    fun clipboardToBytes(onResult: (ByteArray?, String?) -> Unit)

    /** Replace the clipboard with the given schematic bytes; result on render thread. */
    fun bytesToClipboard(bytes: ByteArray, format: String, onResult: (Boolean, String?) -> Unit)
}

object NoopLitematicaBridge : LitematicaBridge {
    private const val UNAVAILABLE = "Litematica is not installed"

    override val isAvailable: Boolean = false

    override fun loadSchematic(file: File, name: String, onResult: (Boolean, String?) -> Unit) =
        onResult(false, UNAVAILABLE)

    override fun listExportSources(): List<ExportSource> = emptyList()

    override fun currentSelectionSource(): ExportSource? = null

    override fun exportToBytes(source: ExportSource, onResult: (ByteArray?, String?) -> Unit) =
        onResult(null, UNAVAILABLE)

    override fun schematicsDirectory(): Path? = null

    override fun loadRenderSource(source: ExportSource, onResult: (SchematicRenderSource?, String?) -> Unit) =
        onResult(null, "Litematica not available")
}

object NoopWorldEditBridge : WorldEditBridge {
    private const val UNAVAILABLE = "WorldEdit is not installed"

    override val isAvailable: Boolean = false

    override fun clipboardToBytes(onResult: (ByteArray?, String?) -> Unit) =
        onResult(null, UNAVAILABLE)

    override fun bytesToClipboard(bytes: ByteArray, format: String, onResult: (Boolean, String?) -> Unit) =
        onResult(false, UNAVAILABLE)
}

/** Global bridge registry; Plan 4 swaps in real implementations during client init. */
object Bridges {
    var litematica: LitematicaBridge = NoopLitematicaBridge
    var worldEdit: WorldEditBridge = NoopWorldEditBridge
}
