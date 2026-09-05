import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import io.schemat.schematioConnector.utils.WorldEditUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs only in the isolated server created by smoke-clipboard.py. */
public final class ClipboardProbe extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getScheduler().runTask(this, () -> {
            try {
                byte[] litematic = Files.readAllBytes(Path.of("fixture.litematic"));
                Clipboard clipboard = WorldEditUtil.INSTANCE.byteArrayToClipboard(litematic);
                if (Boolean.getBoolean("clipboard.expectFailure")) {
                    check(clipboard == null, "old plugin must reproduce the Litematica rejection");
                    getLogger().info("CLIPBOARD_REPRODUCED: Litematica v7 rejected");
                } else {
                    check(clipboard != null, "Litematica v7 must load");
                    verify(clipboard);
                    byte[] sponge = WorldEditUtil.INSTANCE.clipboardToByteArray(clipboard);
                    check(sponge != null, "WorldEdit export");
                    verify(WorldEditUtil.INSTANCE.byteArrayToClipboard(sponge));
                    check(WorldEditUtil.INSTANCE.byteArrayToClipboard(new byte[] {1, 2, 3}) == null,
                        "invalid data must still be rejected");
                    try (var edit = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(getServer().getWorlds().getFirst()))) {
                        BlockVector3 target = BlockVector3.at(10, 100, 10);
                        Operations.complete(new ClipboardHolder(clipboard).createPaste(edit).to(target).build());
                        edit.flushSession();
                        BlockVector3 offset = target.subtract(clipboard.getOrigin());
                        check(edit.getBlock(offset.add(-2, 0, -1)).getBlockType().getId().equals("minecraft:stone"), "pasted stone");
                        check(edit.getBlock(offset.add(-1, 0, -1)).getAsString().contains("axis=x"), "pasted log axis");
                        check(edit.getFullBlock(offset.add(1, 1, 2)).getNbtData().getString("CustomName").contains("Regression chest"), "pasted chest NBT");
                    }
                    getLogger().info("CLIPBOARD_PASS: Litematica v7, multiple regions, negative coordinates, block states, chest NBT, Sponge round trip, invalid data, world paste");
                }
            } catch (Throwable t) {
                getLogger().log(java.util.logging.Level.SEVERE, "CLIPBOARD_FAIL", t);
            } finally {
                getServer().shutdown();
            }
        });
    }

    private static void verify(Clipboard c) {
        check(c != null, "clipboard exists");
        check(c.getBlock(BlockVector3.at(-2, 0, -1)).getBlockType().getId().equals("minecraft:stone"), "stone position");
        check(c.getBlock(BlockVector3.at(-1, 0, -1)).getAsString().contains("axis=x"), "log position and axis");
        var chest = c.getFullBlock(BlockVector3.at(1, 1, 2));
        check(chest.getBlockType().getId().equals("minecraft:chest"), "second region position");
        check(chest.getNbtData() != null && chest.getNbtData().getString("CustomName").contains("Regression chest"), "chest NBT");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
