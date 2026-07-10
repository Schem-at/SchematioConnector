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

dependencies {
    // Core module
    implementation(project(":core"))

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

    // WorldEdit
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.10")

    // ProtocolLib (optional - for advanced features like sign input)
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    // MapEngine (optional - for map preview rendering)
    compileOnly("de.pianoman911:mapengine-api:1.8.11")
}

tasks.shadowJar {
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
// Integration test server: Paper 26.1 + WorldEdit, with this plugin injected.
// Run with:  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :bukkit:runServer
// The shadowJar is added automatically by run-paper. Stop with Ctrl-C / `stop`.
// Run dir: bukkit/run (gitignored).
// ---------------------------------------------------------------------------
tasks.runServer {
    minecraftVersion("26.1.2")
    runDirectory.set(layout.projectDirectory.dir("run"))

    // Paper 26.1+ requires Java 25 to RUN, even though this project builds on Java 21.
    // Use a Java 25 toolchain just for the server JVM (Gradle auto-detects the local JDK).
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    )

    downloadPlugins {
        // WorldEdit 7.4.3 (Bukkit; supports MC 1.21.10–26.1.2). Modrinth version id.
        modrinth("worldedit", "yDUBafTJ")
    }

    doFirst {
        val runDir = layout.projectDirectory.dir("run").asFile
        runDir.mkdirs()
        // Accept the Mojang EULA for this LOCAL test server only (https://aka.ms/MinecraftEULA).
        // Delete bukkit/run/eula.txt if you do not agree.
        runDir.resolve("eula.txt").takeUnless { it.exists() }?.writeText("eula=true\n")
        // Offline mode so the Fabric Loom dev client (runClient) can connect without a paid session.
        runDir.resolve("server.properties").takeUnless { it.exists() }?.writeText(
            buildString {
                appendLine("online-mode=false")
                appendLine("motd=Schematio IPC test server (26.1)")
                appendLine("max-players=5")
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
