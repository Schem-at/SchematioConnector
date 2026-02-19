plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
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
            exec {
                commandLine("bash", reloadScript.absolutePath)
                isIgnoreExitValue = true  // Don't fail build if server isn't running
            }
        } else {
            println("   Run in server console: /plugman reload SchematioConnector")
        }
    }
}
