import com.github.schemat.nucleation.Nucleation;
import com.github.schemat.nucleation.Schematic;

/** Tests the shipped wrapper against a native built for the runner's OS/CPU. */
class NativeSmoke {
    public static void main(String[] args) {
        try (var original = new Schematic("release-smoke")) {
            original.setBlock(0, 0, 0, "minecraft:stone");
            original.setBlock(1, 0, 0, "minecraft:oak_log[axis=x]");
            try (var restored = Schematic.fromBytes(original.toSchematic())) {
                if (restored.blockCount() != 2) throw new AssertionError("Round-trip lost blocks");
                if (!restored.getBlockName(0, 0, 0).orElseThrow().equals("minecraft:stone")) {
                    throw new AssertionError("Round-trip changed a block");
                }
                restored.setBlock(0, 0, 0, "minecraft:diamond_block");
                try (var diff = original.diff(restored, "exact")) {
                    if (!diff.toJson().contains("diamond_block")) {
                        throw new AssertionError("Diff did not preserve the changed block");
                    }
                }
            }
        }
        System.out.println("Nucleation " + Nucleation.version() + ": parse, write, and diff passed");
    }
}
