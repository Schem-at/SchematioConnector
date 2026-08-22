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
    // Local Nucleation JNI fat-jar — resolved as a group:name:version coordinate via
    // flatDir so Loom `include` sees a module component with capabilities (a raw
    // files() dependency cannot be nested as a jar-in-jar). See libs/nucleation-*.jar.
    mavenLocal() // panel-lib (../panel-lib: ./gradlew publishToMavenLocal)
    maven("https://maven.pkg.github.com/Nano112/panel-lib") {
        name = "GitHubPackages"
        credentials {
            username = (findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = (findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
        content { includeGroup("dev.harrison") }
    }
    flatDir {
        name = "LocalLibs"
        // build.gradle.kts is shared across Stonecutter version subprojects, so
        // projectDir points at fabric/versions/<v>; the jar lives in fabric/libs.
        dirs("${rootDir}/fabric/libs")
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

    // Test dependencies — JUnit 5 + kotlin-test, matching the project convention.
    // imgui-java-binding is already on `implementation` so ImVec4 is on the test classpath.
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")

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

    // Nucleation — Rust schematic library (JNI). Parses/iterates schematic bytes
    // (the future VCS diff engine). The local fat JAR embeds the macOS-arm64
    // native as a resource (native/macos-arm64/libnucleation_jvm.dylib) loaded by
    // Nucleation's own NativeLoader. Loom `include` is jar-in-jar so the native
    // resource ships in the mod. Linux/Windows natives + CI fat-JAR are a follow-up
    // (build via nucleation-jvm `./gradlew crossJar`; see docs/nucleation-build.md).
    val nucleationVersion: String = property("nucleation_version") as String
    include(implementation(":nucleation:$nucleationVersion")!!)

    // Shared ImGui overlay (panel-lib). Bundled so users only install SchematioConnector.
    val panelLibVersion: String = property("panellib_version") as String
    add(modImpl, "dev.harrison:panel-lib-mc$mcVersion:$panelLibVersion")
    include("dev.harrison:panel-lib-mc$mcVersion:$panelLibVersion")
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

tasks.withType<Test> {
    useJUnitPlatform()
}

// ============================================================================
// Theme-discipline check: panel source must not contain inline color literals.
// Panels must use ImGuiColors.* constants — never raw hex or numeric ImVec4().
//
// Scan root is configurable via -PthemeCheckDir=<path> for fixture testing.
// When that property is absent, defaults to the real panels directory.
// File glob matches *.kt and *.kt.fixture (supports fixture verification).
// ============================================================================
tasks.register("checkThemeDiscipline") {
    group = "verification"
    description = "Fails the build if ImGui panel source uses inline color literals instead of ImGuiColors.*"

    doLast {
        // buildFile is fabric/build.gradle.kts; its parent is the shared fabric/ module dir
        // (not the Stonecutter versioned subproject dir at fabric/versions/<ver>/).
        val fabricModuleDir = buildFile.parentFile

        val scanDir: File = if (project.hasProperty("themeCheckDir")) {
            val customPath = project.property("themeCheckDir") as String
            val rawFile = File(customPath)
            // If absolute, use directly. Otherwise resolve relative to repo root.
            if (rawFile.isAbsolute) rawFile else File(rootProject.projectDir, customPath)
        } else {
            // Default: real panels dir, relative to fabric module dir.
            File(fabricModuleDir, "src/client/kotlin/io/schemat/connector/fabric/client/ui/imgui/panels")
        }

        if (!scanDir.exists()) {
            logger.lifecycle("checkThemeDiscipline: panels dir not found (${scanDir.path}) — skipping (no-op)")
            return@doLast
        }

        val hexColorRegex = Regex("""0x[0-9A-Fa-f]{8}""")
        val imVec4Regex = Regex("""ImVec4\s*\(""")

        val violations = mutableListOf<String>()

        scanDir.walk()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".kt.fixture")) }
            .sorted()
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val lineNo = idx + 1
                    if (hexColorRegex.containsMatchIn(line)) {
                        violations += "${file.path}:$lineNo  inline hex color literal in panel; use ImGuiColors"
                    }
                    if (imVec4Regex.containsMatchIn(line)) {
                        violations += "${file.path}:$lineNo  numeric ImVec4 constructor in panel; use ImGuiColors"
                    }
                }
            }

        if (violations.isEmpty()) {
            logger.lifecycle("checkThemeDiscipline: OK — no inline color literals found in ${scanDir.path}")
        } else {
            throw GradleException(
                "Theme-discipline violations (use ImGuiColors.* instead of inline literals):\n" +
                    violations.joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkThemeDiscipline")
}

// imgui-java-lwjgl3 (and its transitive LWJGL native JARs) are not needed for
// unit tests and several native classifiers don't exist in Maven Central.
// Exclude them from the test runtime so resolution doesn't fail.
