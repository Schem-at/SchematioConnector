// Test-only Rhino script executed by MC-Inspector after joining the test world.
// The runner sets schematio.features.output to an isolated output directory.
(function () {
    var System = Packages.java.lang.System;
    var Files = Packages.java.nio.file.Files;
    var Path = Packages.java.nio.file.Path;
    var Schematic = Packages.com.github.schemat.nucleation.Schematic;
    var render = Packages.io.schemat.connector.fabric.client.render;
    var out = Path.of(System.getProperty("schematio.features.output"));
    Files.createDirectories(out);
    mc.options.pauseOnLostFocus = false;
    var schematic = new Schematic("release-placement");
    schematic.setBlock(0, 0, 0, "minecraft:stone");
    schematic.setBlock(1, 0, 0, "minecraft:oak_log[axis=x]");
    var file = out.resolve("placement.litematic");
    Files.write(file, schematic.toLitematic());
    schematic.close();
    var bridge = Packages.io.schemat.connector.fabric.client.integration.Bridges.INSTANCE.getLitematica();
    bridge.loadSchematic(file.toFile(), "release-placement", new JavaAdapter(Packages.kotlin.jvm.functions.Function2, {
        invoke: function (ok, error) {
            if (!ok) { System.setProperty("schematio.features.placement", "FAIL: " + error); return Packages.kotlin.Unit.INSTANCE; }
            var source = bridge.currentSelectionSource();
            bridge.exportToBytes(source, new JavaAdapter(Packages.kotlin.jvm.functions.Function2, {
                invoke: function (bytes, problem) {
                    try {
                        if (bytes == null) throw new Error(String(problem));
                        var loaded = Schematic.fromBytes(bytes);
                        try {
                            if (loaded.blockCount() != 2 || String(loaded.getBlockName(0,0,0).orElse("")) != "minecraft:stone" || String(loaded.getBlock(1,0,0).get()).indexOf("axis=x") < 0) {
                                throw new Error("Placement export changed block contents: " + loaded.print());
                            }
                        } finally { loaded.close(); }
                        Files.write(out.resolve("placement-export.litematic"), bytes);
                        System.setProperty("schematio.features.placement", "PASS");
                    } catch (e) { System.setProperty("schematio.features.placement", "FAIL: " + e); }
                    return Packages.kotlin.Unit.INSTANCE;
                }
            }));
            return Packages.kotlin.Unit.INSTANCE;
        }
    }));
    var chest = new Schematic("release-chest");
    chest.setBlock(0, 0, 0, "minecraft:chest[facing=north,type=single,waterlogged=false]");
    var source = render.data.NucleationSnapshotSource.INSTANCE.snapshotFromBytes(chest.toSchematic());
    chest.close();
    var target = new render.OffscreenTarget(640, 360);
    var pose = new render.CameraPose(45, 30, 1.6, render.Projection.ISOMETRIC, 0, 0, 55);
    render.OffscreenSchematicRenderer.INSTANCE.render(source, pose, target, render.BackgroundMode.TRANSPARENT);
    target.readPng(true, new JavaAdapter(Packages.kotlin.jvm.functions.Function1, {
        invoke: function (bytes) {
            try {
                if (bytes == null) throw new Error("PNG readback failed");
                var path = out.resolve("chest-transparent.png");
                Files.write(path, bytes);
                var image = Packages.javax.imageio.ImageIO.read(path.toFile());
                var colors = new Packages.java.util.HashSet();
                var visible = 0, clear = 0;
                for (var y = 0; y < image.getHeight(); y++) for (var x = 0; x < image.getWidth(); x++) {
                    var color = image.getRGB(x,y);
                    if ((color >>> 24) == 0) clear++; else { visible++; colors.add(color); }
                }
                if (visible < 100 || clear < 100 || colors.size() < 32) throw new Error("Missing chest or alpha: pixels=" + visible + ", colors=" + colors.size());
                System.setProperty("schematio.features.chest", "PASS");
            } catch (e) { System.setProperty("schematio.features.chest", "FAIL: " + e); }
            finally { target.close(); }
            return Packages.kotlin.Unit.INSTANCE;
        }
    }));
    return "started";
})();
