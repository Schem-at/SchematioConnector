import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Test-only JVM agent: render real Minecraft models and check PNG readback. Never bundled. */
public class ClientSmokeAgent {
    private static ClassLoader loader;
    private static Object client;
    private static boolean intermediary;
    private static final String RENDER = "io.schemat.connector.fabric.client.render.";
    private static final AtomicInteger pending = new AtomicInteger(4);

    public static void premain(String args, Instrumentation instrumentation) {
        var thread = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 180_000;
                while (System.currentTimeMillis() < deadline) {
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        if (!type.getName().equals("net.minecraft.client.Minecraft") && !type.getName().equals("net.minecraft.class_310")) continue;
                        intermediary = type.getName().equals("net.minecraft.class_310");
                        Object candidate = type.getMethod(mcName("getInstance", "method_1551")).invoke(null);
                        if (candidate == null || !(boolean) type.getMethod(mcName("isGameLoadFinished", "method_53466")).invoke(candidate)) continue;
                        var gameDir = ((java.io.File) type.getField(mcName("gameDirectory", "field_1697")).get(candidate)).toPath().toRealPath();
                        if (!gameDir.equals(Path.of(System.getProperty("schematio.smoke.output")).toRealPath())) {
                            throw new IllegalStateException("Smoke client must use its isolated game directory; got " + gameDir);
                        }
                        loader = type.getClassLoader();
                        client = candidate;
                        // Resource reload has finished; run on Minecraft's render thread.
                        type.getMethod("execute", Runnable.class).invoke(client, (Runnable) ClientSmokeAgent::render);
                        return;
                    }
                    Thread.sleep(1000);
                }
                fail(new IllegalStateException("Client startup timed out"));
            } catch (Throwable t) { fail(t); }
        }, "Schematio release smoke");
        thread.setDaemon(true);
        thread.start();
    }

    private static String mcName(String named, String mapped) { return intermediary ? mapped : named; }

    private static Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private static Object singleton(String name) throws Exception { return type(name).getField("INSTANCE").get(null); }

    private static void render() {
        try {
            var schematicClass = type("com.github.schemat.nucleation.Schematic");
            var schematic = schematicClass.getConstructor(String.class).newInstance("smoke");
            byte[] bytes;
            try {
                var set = schematicClass.getMethod("setBlock", int.class, int.class, int.class, String.class);
                set.invoke(schematic, 0, 0, 0, "minecraft:stone");
                set.invoke(schematic, 1, 0, 0, "minecraft:oak_log[axis=x]");
                set.invoke(schematic, 0, 1, 0, "minecraft:glass");
                set.invoke(schematic, 1, 0, 1, "minecraft:water[level=0]");
                bytes = (byte[]) schematicClass.getMethod("toSchematic").invoke(schematic);
            } finally { schematicClass.getMethod("close").invoke(schematic); }
            checkAxiom(bytes);
            var factory = singleton(RENDER + "data.NucleationSnapshotSource");
            var source = factory.getClass().getMethod("snapshotFromBytes", byte[].class).invoke(factory, bytes);

            var renderer = singleton(RENDER + "OffscreenSchematicRenderer");
            for (String projection : List.of("ISOMETRIC", "PERSPECTIVE")) for (String mode : List.of("STUDIO", "TRANSPARENT")) {
                Object projectionMode = Enum.valueOf((Class) type(RENDER + "Projection"), projection);
                var pose = type(RENDER + "CameraPose").getConstructor(float.class, float.class, float.class,
                    projectionMode.getClass(), float.class, float.class, float.class)
                    .newInstance(45f, 30f, projection.equals("PERSPECTIVE") ? 3f : 1.6f, projectionMode, 0f, 0f, 55f);
                String label = projection.toLowerCase() + "-" + mode.toLowerCase();
                var target = type(RENDER + "OffscreenTarget").getConstructor(int.class, int.class).newInstance(640, 360);
                Object background = Enum.valueOf((Class) type(RENDER + "BackgroundMode"), mode);
                renderer.getClass().getMethod("render", type(RENDER + "SchematicRenderSource"),
                    type(RENDER + "CameraPose"), target.getClass(), background.getClass()).invoke(renderer, source, pose, target, background);
                Class<?> callbackType = type("kotlin.jvm.functions.Function1");
                Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, arguments) -> {
                    if (method.getName().equals("invoke")) {
                        try {
                            byte[] png = (byte[]) arguments[0];
                            if (png == null) throw new AssertionError("PNG readback failed: " + mode);
                            var path = Path.of(System.getProperty("schematio.smoke.output"), label + ".png");
                            Files.write(path, png);
                            var image = ImageIO.read(path.toFile());
                            Set<Integer> colors = new HashSet<>();
                            int clear = 0, drawn = 0;
                            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                                int color = image.getRGB(x, y);
                                colors.add(color);
                                if ((color >>> 24) == 0) clear++; else drawn++;
                            }
                            if (colors.size() < 32 || drawn < 100) throw new AssertionError("Blank schematic render: " + mode);
                            if (mode.equals("TRANSPARENT") && clear < 100) throw new AssertionError("PNG lost alpha");
                            System.out.println("SCHEMAT-SMOKE PASS " + label + " colors=" + colors.size() + " clear=" + clear);
                            target.getClass().getMethod("close").invoke(target);
                            if (pending.decrementAndGet() == 0) {
                                renderer.getClass().getMethod("releaseCache").invoke(renderer);
                                Files.writeString(path.getParent().resolve("passed.txt"), "Client initialization, model tessellation, studio capture, alpha capture passed\n");
                                client.getClass().getMethod(mcName("stop", "method_1592")).invoke(client);
                            }
                        } catch (Throwable t) { fail(t); }
                        return type("kotlin.Unit").getField("INSTANCE").get(null);
                    }
                    return null;
                });
                target.getClass().getMethod("readPng", boolean.class, callbackType).invoke(target, mode.equals("TRANSPARENT"), callback);
            }
        } catch (Throwable t) { fail(t); }
    }

    private static void checkAxiom(byte[] bytes) throws Exception {
        var fabricLoader = type("net.fabricmc.loader.api.FabricLoader");
        var instance = fabricLoader.getMethod("getInstance").invoke(null);
        boolean installed = (boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, "axiom");
        var integration = singleton("io.schemat.connector.fabric.client.integration.axiom.AxiomIntegration");
        boolean available = (boolean) integration.getClass().getMethod("getAvailable").invoke(integration);
        if (installed != available) throw new AssertionError("Axiom adapter availability does not match installed pinned contract");
        if (!installed) { System.out.println("SCHEMAT-INTEGRATION PASS optional editors absent"); return; }
        var adapter = type("io.schemat.axiom.AxiomClipboardAdapter");
        var prepare = adapter.getDeclaredMethod("prepare", byte[].class, String.class); prepare.setAccessible(true);
        var current = adapter.getDeclaredMethod("current"); current.setAccessible(true);
        var before = current.invoke(null);
        var prepared = prepare.invoke(null, bytes, "Release fixture");
        var clipboardType = type("com.moulberry.axiom.clipboard.ClipboardObject");
        if (!"Release fixture".equals(clipboardType.getMethod("name").invoke(prepared))) throw new AssertionError("Clipboard name lost");
        var region = clipboardType.getMethod("blockRegion").invoke(prepared);
        if (((Number) region.getClass().getMethod("count").invoke(region)).longValue() < 4) throw new AssertionError("Clipboard blocks lost");
        if (before != current.invoke(null)) throw new AssertionError("Preparation replaced Axiom clipboard");
        System.out.println("SCHEMAT-INTEGRATION PASS Axiom registration and schematic preparation; clipboard preserved");
    }

    private static void fail(Throwable t) {
        System.err.println("SCHEMAT-SMOKE FAILED");
        t.printStackTrace();
        System.exit(1);
    }
}
