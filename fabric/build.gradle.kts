plugins {
    id("fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

// ============================================================================
// Stonecutter multi-version build.
// This script is evaluated once per Minecraft version (:fabric:<version>).
// - The MC version comes from Stonecutter (stonecutter.current.version).
// - Per-version dependency pins come from fabric/versions/<version>/gradle.properties.
// - Shared dependency pins (loader, FLK, conditional-mixin) come from the
//   root gradle.properties.
// All versions use official Mojang mappings (26.x has no Yarn).
// ============================================================================
val mcVersion: String = stonecutter.current.version
val loaderVersion: String = property("deps.fabric_loader") as String
val fabricApiVersion: String = property("deps.fabric_api") as String
val flkVersion: String = property("deps.flk") as String
// fabric.mod.json `depends` floors — decoupled from the build versions above so
// the shipped mod installs on older loader/FLK than we compile against.
val loaderMin: String = property("deps.fabric_loader_min") as String
val flkMin: String = property("deps.flk_min") as String
val conditionalMixinVersion: String = property("deps.conditional_mixin") as String
val litematicaVersion: String = property("deps.litematica") as String
val malilibVersion: String = property("deps.malilib") as String
val mcCompat: String = property("mod.mc_compat") as String
val worldeditVersion: String = property("worldedit_version") as String

// MC 26.x: requires Java 25 (its class files are major version 69) and ships
// UNOBFUSCATED - there are no mappings at all (this is why Yarn was dropped
// for 26.x). 1.21.x targets Java 21 and uses official Mojang mappings.
val is26x: Boolean = mcVersion.substringBefore('.').toInt() >= 26
val javaVer: Int = if (is26x) 25 else 21

base {
    archivesName.set("SchematioConnector-Fabric-mc$mcVersion")
}

repositories {
    maven("https://maven.fabricmc.net/") {
        name = "Fabric"
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "EngineHub"
    }
    exclusiveContent {
        forRepository {
            maven("https://api.modrinth.com/maven") {
                name = "Modrinth"
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") {
        name = "DevAuth"
    }
    maven("https://maven.fallenbreath.me/releases") {
        name = "FallenBreath"
    }
}

dependencies {
    // On 1.21.x loom runs in remapping mode and mod dependencies go through the
    // `mod*` configurations. On unobfuscated 26.x loom runs in no-remap mode
    // (fabric.loom.disableObfuscation=true) where those configurations do not
    // exist - mods are plain dependencies (matches the official 26.x example mod).
    val modImpl = if (is26x) "implementation" else "modImplementation"
    val modRuntime = if (is26x) "runtimeOnly" else "modRuntimeOnly"

    // Core module
    implementation(project(":core"))

    // Minecraft & Fabric
    minecraft("com.mojang:minecraft:$mcVersion")
    // 26.x is unobfuscated: no mappings exist at all (this is why Yarn was
    // dropped for 26.x). 1.21.x uses official Mojang mappings.
    if (!is26x) {
        add("mappings", loom.officialMojangMappings())
    }
    add(modImpl, "net.fabricmc:fabric-loader:$loaderVersion")

    // Fabric API modules we need
    add(modImpl, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Fabric Language Kotlin for Kotlin entrypoints
    add(modImpl, "net.fabricmc:fabric-language-kotlin:$flkVersion")

    // WorldEdit core API (optional - for clipboard operations when WorldEdit is installed)
    // Uses worldedit-core directly to avoid Loom version mismatch with worldedit-fabric
    compileOnly("com.sk89q.worldedit:worldedit-core:$worldeditVersion") {
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "it.unimi.dsi") // Avoid strict version conflict with MC's bundled FastUtil
    }

    // HTTP dependencies (needed at compile time and runtime)
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpcore:4.4.16")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")

    // Client-only: Litematica and MaLiLib (compile + dev runtime, not bundled).
    // Modrinth version IDs pinned per MC version in fabric/versions/<ver>/gradle.properties.
    add(modImpl, "maven.modrinth:litematica:$litematicaVersion")
    add(modImpl, "maven.modrinth:malilib:$malilibVersion")

    // conditional-mixin (required by Litematica and MaLiLib, and by our @Restriction mixins).
    // Bundled jar-in-jar so the mixin config plugin always classloads, even without Litematica.
    add(modImpl, "me.fallenbreath:conditional-mixin-fabric:$conditionalMixinVersion")
    include("me.fallenbreath:conditional-mixin-fabric:$conditionalMixinVersion")

    // DevAuth for authenticated dev sessions (runtime only, not bundled)
    add(modRuntime, "me.djtheredstoner:DevAuth-fabric:1.2.2")

    // Include core module and its dependencies in the JAR
    include(project(":core"))

    // Core module dependencies that need to be bundled
    include("com.google.code.gson:gson:2.11.0")
    include("org.apache.httpcomponents:httpclient:4.5.14")
    include("org.apache.httpcomponents:httpcore:4.4.16")
    include("org.apache.httpcomponents:httpmime:4.5.14")
    include("com.auth0:java-jwt:4.4.0")
    // java-jwt's JWTDecoder needs Jackson at runtime. Loom's `include` is
    // jar-in-jar and NON-transitive, so each Jackson artifact must be listed
    // explicitly or the shipped mod hits NoClassDefFoundError on JWT.decode()
    // (java-jwt 4.4.0 -> jackson-databind 2.14.2 -> jackson-core/-annotations).
    include("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    include("com.fasterxml.jackson.core:jackson-core:2.14.2")
    include("com.fasterxml.jackson.core:jackson-annotations:2.14.2")
    include("commons-logging:commons-logging:1.2")
    include("commons-codec:commons-codec:1.15")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        register("schematioconnector") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }

    // Disable Javadoc generation from mod dependencies to avoid namespace issues with newer Fabric API
    // This fixes the "Javadoc provided by mod must have an intermediary source namespace" error in 1.21.11+
    @Suppress("UnstableApiUsage")
    enableModProvidedJavadoc.set(false)

    runs {
        named("client") {
            client()
            configName = "Fabric Client ($mcVersion)"
            ideConfigGenerated(stonecutter.current.isActive)
            // Shared run dir across versions: fabric/run
            runDir("../../run")
            vmArg("-Ddevauth.enabled=true")
        }
        named("server") {
            server()
            configName = "Fabric Server ($mcVersion)"
            ideConfigGenerated(stonecutter.current.isActive)
            runDir("../../run")
        }
    }
}

// Covers BOTH processResources (main: fabric.mod.json) and
// processClientResources (client: *.mixins.json) - the environment source
// sets are split.
tasks.withType<ProcessResources>().configureEach {
    inputs.property("version", project.version)
    inputs.property("minecraft_compat", mcCompat)
    inputs.property("loader_min", loaderMin)
    inputs.property("flk_min", flkMin)
    inputs.property("java_version", javaVer)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_compat" to mcCompat,
            "loader_min" to loaderMin,
            "flk_min" to flkMin,
            "java_version" to javaVer.toString()
        )
    }

    filesMatching("*.mixins.json") {
        expand("mixin_java" to "JAVA_$javaVer")
    }
}

tasks.jar {
    from(rootProject.file("LICENSE")) {
        rename { "${it}_${base.archivesName.get()}" }
    }
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVer)
    targetCompatibility = JavaVersion.toVersion(javaVer)
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVer)
    }
}

kotlin {
    jvmToolchain(javaVer)
}

// Build this version's remapped jar and collect it into the shared output dir
// build/libs/<mod version>/ at the repository root.
// `./gradlew buildAndCollect` from the repo root builds ALL versions.
tasks.register<Copy>("buildAndCollect") {
    group = "build"
    // On mapping-less 26.x builds loom may not register remapJar; fall back to jar.
    from(tasks.named(if ("remapJar" in tasks.names) "remapJar" else "jar"))
    into(rootProject.layout.buildDirectory.dir("libs/${project.version}"))
    dependsOn("build")
}
