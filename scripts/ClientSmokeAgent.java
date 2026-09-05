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
    private static final String RENDER = "io.schemat.connector.fabric.client.render.";
    private static final AtomicInteger pending = new AtomicInteger(4);

    public static void premain(String args, Instrumentation instrumentation) {
        var thread = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 180_000;
                while (System.currentTimeMillis() < deadline) {
                    for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                        if (!type.getName().equals("net.minecraft.client.Minecraft")) continue;
                        Object candidate = type.getMethod("getInstance").invoke(null);
                        if (candidate == null || !(boolean) type.getMethod("isGameLoadFinished").invoke(candidate)) continue;
                        var gameDir = ((java.io.File) type.getField("gameDirectory").get(candidate)).toPath().toRealPath();
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
                                client.getClass().getMethod("stop").invoke(client);
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

    private static void fail(Throwable t) {
        System.err.println("SCHEMAT-SMOKE FAILED");
        t.printStackTrace();
        System.exit(1);
    }
}
