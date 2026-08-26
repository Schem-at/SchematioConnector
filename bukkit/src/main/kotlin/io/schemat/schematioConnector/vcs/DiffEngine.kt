package io.schemat.schematioConnector.vcs

import io.schemat.connector.core.vcs.DiffParser
import io.schemat.connector.core.vcs.DiffRegion

/**
 * Base-vs-other diff of two schematic byte payloads through Nucleation, returned as
 * the clustered [DiffRegion]s the session/renderer consume.
 *
 * Direction matters: ADDED/CHANGED carry the *other* side's blocks, REMOVED the
 * *base* side's. The RESOLVE flow diffs new-HEAD (base/THEIRS) vs the player's edit
 * (other/MINE) so a MINE choice means "apply my cells".
 *
 * Callers must check [NucleationRuntime.available] first.
 */
object DiffEngine {

    /** Nucleation diff preset; `exact` is the per-block preset the viewer needs. */
    const val DIFF_PRESET = "exact"

    fun computeRegions(baseBytes: ByteArray, otherBytes: ByteArray): List<DiffRegion> =
        SchematicBridge.bytesToSchematic(baseBytes).use { base ->
            SchematicBridge.bytesToSchematic(otherBytes).use { other ->
                base.diff(other, DIFF_PRESET).use { diff ->
                    DiffParser.parseRegions(diff.toJson())
                }
            }
        }
}
