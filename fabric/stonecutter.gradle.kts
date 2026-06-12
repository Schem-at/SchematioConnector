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
