package io.schemat.axiom;

import com.moulberry.axiom.AxiomClient;
import com.moulberry.axiom.clipboard.Clipboard;
import com.moulberry.axiom.clipboard.ClipboardObject;
import com.moulberry.axiom.clipboard.Placement;
import com.moulberry.axiom.editor.schematic.SchematicLoader;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** The only schematic-transfer dependency on Axiom internals, pinned to Axiom 6.0.5 for each supported Minecraft version. */
final class AxiomClipboardAdapter {
    static final long MAX_VOLUME = 2_000_000;
    static boolean permitted() { return AxiomClient.hasPermission(AxiomPermission.CAN_IMPORT_BLOCKS); }
    static ClipboardObject current() { return Clipboard.INSTANCE.getClipboard(); }
    static boolean placing() { return Placement.INSTANCE.isPlacing(); }

    static ClipboardObject prepare(byte[] bytes, String name) throws IOException {
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(64L * 1024 * 1024));
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        int version = schematic.getIntOr("Version", -1);
        if (version != 2 && version != 3) throw new IOException("Expected a Sponge v2 or v3 schematic.");
        int width = Short.toUnsignedInt(schematic.getShortOr("Width", (short) 0));
        int height = Short.toUnsignedInt(schematic.getShortOr("Height", (short) 0));
        int length = Short.toUnsignedInt(schematic.getShortOr("Length", (short) 0));
        if (width == 0 || height == 0 || length == 0 || (long) width * height * length > MAX_VOLUME)
            throw new IOException("Axiom imports are limited to 2 million blocks in volume.");
        // Axiom 6.0.5's schematic loader does not transfer entities into its placement list.
        // Reject explicitly until that conversion has been implemented and verified.
        if (!schematic.getListOrEmpty("Entities").isEmpty())
            throw new IOException("This build contains entities. Use Axiom's native blueprint workflow for now.");
        ClipboardObject result = SchematicLoader.loadSponge(schematic);
        if (result.blockRegion().isEmpty()) throw new IOException("The schematic contains no blocks.");
        return new ClipboardObject.Anonymous(result.blockRegion(), result.blockEntities(), result.entities(),
                name, result.preferredYaw(), result.containsAir(), schematic);
    }

    static void commit(ClipboardObject prepared) {
        Clipboard.INSTANCE.setClipboard(prepared);
    }
}
