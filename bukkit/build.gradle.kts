plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
    // Spins up a real Paper server for integration testing (./gradlew :bukkit:runServer).
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

base {
    archivesName.set("SchematioConnector-Paper")
}

// Dev server path for hot-reload deployment
val devServerPath = file("${System.getProperty("user.home")}/Desktop/mc_dev_server_1.21.8")
val pluginsFolder = devServerPath.resolve("plugins")

repositories {
    // Same local Nucleation fat JAR the fabric module bundles (fabric/libs/nucleation-<version>.jar,
    // natives embedded as resources — see docs/nucleation-build.md). flatDir keeps the coordinate
    // form (:nucleation:<version>) consistent with fabric/build.gradle.kts.
    flatDir {
        name = "LocalLibs"
        dirs("${rootDir}/fabric/libs")
    }
}

dependencies {
    // Core module
    implementation(project(":core"))

    // Nucleation — Rust schematic library (JNI). Powers the in-game diff viewer
    // (parse/diff/compose on .schem bytes). Shaded into the plugin jar (runtimeClasspath
    // flows into shadowJar); NOT relocated — the embedded native registers JNI methods
    // against the com.github.schemat.nucleation package names. Natives load lazily via
    // NucleationRuntime; on unsupported platforms diff features degrade gracefully.
    val nucleationVersion: String = property("nucleation_version") as String
    implementation(":nucleation:$nucleationVersion")

    // HTTP Client (needed directly by some commands that use HttpResponse/HttpEntity)
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")

    // JWT (needed by SetTokenSubcommand)
    implementation("com.auth0:java-jwt:4.4.0")

    // Adventure (Paper bundles this, but we need it for compilation)
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")

    // GUI Library (1.49 for 1.21.8 support)
    implementation("xyz.xenondevs.invui:invui:1.49")
    implementation("xyz.xenondevs.invui:invui-kotlin:1.49")

    // Paper API (1.21.8 for Dialog API support)
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    // Also needed for tests: PluginIpcService implements PluginMessageListener, so simply
    // classloading it (e.g. to call the pure PluginIpcService.wantsAttestation gate) requires
    // Paper's classes to be resolvable at runtime; testImplementation additionally lets test
    // sources reference Bukkit/Paper types directly (e.g. to mockk a greet() call).
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    // WorldEdit
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.10")
    testImplementation("com.sk89q.worldedit:worldedit-bukkit:7.3.10")

    // ProtocolLib (optional - for advanced features like sign input)
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    // MapEngine (optional - for map preview rendering)
    compileOnly("de.pianoman911:mapengine-api:1.8.11")
}

tasks.shadowJar {
    from(rootProject.file("LICENSE"))
    archiveClassifier.set("")
    mergeServiceFiles()
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // Relocate dependencies to avoid conflicts
    relocate("xyz.xenondevs.invui", "io.schemat.libs.invui")
    relocate("xyz.xenondevs.inventoryaccess", "io.schemat.libs.inventoryaccess")
    relocate("com.google.gson", "io.schemat.libs.gson")
    relocate("org.apache.http", "io.schemat.libs.apache.http")
    relocate("com.auth0.jwt", "io.schemat.libs.jwt")
}

tasks {
    jar {
        enabled = false  // Disable thin JAR, only use shadowJar
    }

    build {
        dependsOn(shadowJar)
    }
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// ---------------------------------------------------------------------------
// Integration test server: Paper + WorldEdit + ProtocolLib, with this plugin injected.
// Run with:  ./gradlew :bukkit:runServer                     (defaults to 1.21.8)
//            ./gradlew :bukkit:runServer -PpaperRunVersion=26.1.2
// The shadowJar is added automatically by run-paper. Stop with Ctrl-C / `stop`.
// Run dir: bukkit/run/<version> (gitignored) — one world per MC version, so
// switching versions never downgrade-corrupts a world.
// ---------------------------------------------------------------------------
val paperRunVersion: String = (project.findProperty("paperRunVersion") as String?) ?: "1.21.8"

tasks.runServer {
    minecraftVersion(paperRunVersion)
    val versionedRunDir = layout.projectDirectory.dir("run/$paperRunVersion")
    runDirectory.set(versionedRunDir)

    // Paper 26.x requires Java 25 to RUN; 1.21.x runs on 21. The project still
    // BUILDS on Java 21 either way — this only picks the server JVM.
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(if (paperRunVersion.startsWith("26")) 25 else 21))
        }
    )

    downloadPlugins {
        // WorldEdit: 7.4.x is compiled for Java 25 (26.x servers); 1.21.x servers run
        // Java 21 and need 7.3.x. Modrinth version ids.
        modrinth("worldedit", if (paperRunVersion.startsWith("26")) "qNuPcliz" else "2YDdVDmG")
        // ProtocolLib 5.4.0 — required by the in-game diff viewer's per-player
        // display-entity renderer.
        github("dmulloy2", "ProtocolLib", "5.4.0", "ProtocolLib.jar")
    }

    doFirst {
        val runDir = versionedRunDir.asFile
        runDir.mkdirs()
        // Accept the Mojang EULA for this LOCAL test server only (https://aka.ms/MinecraftEULA).
        // Delete the run dir's eula.txt if you do not agree.
        runDir.resolve("eula.txt").takeUnless { it.exists() }?.writeText("eula=true\n")
        // Online mode so players get their real Mojang UUID (v4) — required to link to a
        // schemat.io account (attestation / clipboard flows). The Loom dev client
        // authenticates via DevAuth (-Ddevauth.enabled=true in fabric runClient); complete
        // its one-time Microsoft device-code login on first launch. (Only written when
        // server.properties is absent, so an existing run dir keeps its current setting.)
        runDir.resolve("server.properties").takeUnless { it.exists() }?.writeText(
            buildString {
                appendLine("online-mode=true")
                appendLine("motd=Schematio test server ($paperRunVersion)")
                appendLine("max-players=5")
            }
        )
        // Bootstrap the plugin config against the LOCAL schemat.io backend (Herd,
        // self-signed cert) so the server is API-ready on first boot. Only written
        // if absent — drop your community token in via `/schematio settoken <jwt>`
        // or edit the file.
        val pluginCfgDir = runDir.resolve("plugins/SchematioConnector")
        pluginCfgDir.mkdirs()
        pluginCfgDir.resolve("config.yml").takeUnless { it.exists() }?.writeText(
            buildString {
                appendLine("api-endpoint: \"https://schemati.test/api/v1\"")
                appendLine("community-token: \"\"")
                appendLine("trust-all-certificates: true")
            }
        )
    }
}

// Deploy task - copies JAR to dev server plugins folder and reloads
tasks.register<Copy>("deploy") {
    dependsOn(tasks.shadowJar)

    from(tasks.shadowJar.get().archiveFile)
    into(pluginsFolder)

    // Rename to a consistent name for easier plugman reload
    rename { "SchematioConnector.jar" }

    doFirst {
        if (!devServerPath.exists()) {
            throw GradleException("Dev server not found at: $devServerPath")
        }
        println("Deploying to: $pluginsFolder")
    }

    doLast {
        println("JAR deployed!")

        // Auto-reload if server is running in tmux
        val reloadScript = rootProject.file("scripts/reload-plugin.sh")
        if (reloadScript.exists()) {
            providers.exec {
                commandLine("bash", reloadScript.absolutePath)
                isIgnoreExitValue = true  // Don't fail build if server isn't running
            }
        } else {
            println("   Run in server console: /plugman reload SchematioConnector")
        }
    }
}
