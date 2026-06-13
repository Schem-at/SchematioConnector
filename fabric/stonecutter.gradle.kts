plugins {
    id("dev.kikugie.stonecutter")
}

// The version the working tree is currently switched to.
// Change with: ./gradlew "Set active project to <version>"  (group: stonecutter)
stonecutter active "1.21.11"

// Build-all entry point: one remapped jar per Minecraft version, collected
// into <repo>/build/libs/<mod version>/.
// 26.1 is a full shipping target: the client UI/render port for the MC 26.x
// GUI framework (GuiGraphics -> GuiGraphicsExtractor/ActiveTextCollector) and
// the reworked block/fluid render pipeline is complete. It builds with the
// Java 25 toolchain via mapping-less loom.
val buildableVersions = listOf("1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1")
tasks.register("buildAllVersions") {
    group = "build"
    description = "Builds and collects the fabric jar for every supported Minecraft version."
    buildableVersions.forEach { dependsOn(":fabric:$it:buildAndCollect") }
}

// ----------------------------------------------------------------------------
// Single-version run delegation (foot-gun fix).
//
// Stonecutter registers a `runClient`/`runServer` task in EVERY per-version
// subproject (:fabric:1.21.8, :fabric:1.21.11, ...). An unqualified invocation
// like `./gradlew :fabric:runClient` (the path the default IntelliJ
// "SchematioConnector [runClient]" config uses) name-matches the task in ALL of
// them and launches every client at once -> they collide on the shared run dir,
// port (BindException: Address already in use) and DevAuth login.
//
// These :fabric-level aggregator tasks delegate to ONLY the ACTIVE version
// (from the Stonecutter controller, currently `stonecutter active "1.21.11"`), so a
// bare run launches exactly one client. Per-version tasks
// (`:fabric:<ver>:runClient`) are untouched - use those, or the
// `.run/Client <ver>.run.xml` IntelliJ configs, to run a specific version.
// In the controller script `stonecutter.current` is the ACTIVE version
// (StonecutterControllerExtension.current == tree.current); `.project` is the
// subproject name (e.g. "1.21.11"). Fall back to the VCS version if somehow unset.
val activeVersion: String = (stonecutter.current ?: stonecutter.vcsVersion).project
listOf("runClient" to "client", "runServer" to "server").forEach { (taskName, kind) ->
    tasks.register(taskName) {
        group = "fabric"
        description = "Runs the active Minecraft version's $kind ($activeVersion). " +
            "Use :fabric:<version>:$taskName for a specific version."
        dependsOn(":fabric:$activeVersion:$taskName")
    }
}

stonecutter parameters {
    replacements {
        // Mojang mappings renamed ResourceLocation -> Identifier in 1.21.11.
        // Sources are kept in 1.21.11+ form (Identifier); Stonecutter swaps the
        // name back for 1.21.8-1.21.10 builds.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
        // 1.21.9 introduced GUI input event objects. On 1.21.8 the same-named
        // shims in io.schemat.connector.fabric.client.compat are used instead
        // (see compat/InputEvents.kt); only the imports differ.
        string(current.parsed >= "1.21.9") {
            replace(
                "io.schemat.connector.fabric.client.compat.MouseButtonEvent",
                "net.minecraft.client.input.MouseButtonEvent"
            )
            replace(
                "io.schemat.connector.fabric.client.compat.KeyEvent",
                "net.minecraft.client.input.KeyEvent"
            )
            replace(
                "io.schemat.connector.fabric.client.compat.CharacterEvent",
                "net.minecraft.client.input.CharacterEvent"
            )
        }
    }
}
