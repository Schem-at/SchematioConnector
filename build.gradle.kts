plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.kapt") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
    id("org.jetbrains.dokka") version "1.9.20"
}

group = "io.schemat"

// Version from gradle.properties (semantic versioning)
val versionMajor: String by project
val versionMinor: String by project
val versionPatch: String by project
version = "$versionMajor.$versionMinor.$versionPatch"

// Dev server path for hot-reload deployment
val devServerPath = file("${System.getProperty("user.home")}/Desktop/mc_dev_server_1.21.8")
val pluginsFolder = devServerPath.resolve("plugins")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "enginehub"
    }
    maven("https://repo.xenondevs.xyz/releases")
    maven("https://repo.minceraft.dev/releases/") {
        name = "minceraft"
    }
    maven("https://jitpack.io") {
        name = "jitpack"
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    
    // JSON
    implementation("com.google.code.gson:gson:2.11.0")
    
    // HTTP Client - keeping Apache for now, can migrate to OkHttp later
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")
    
    // JWT
    implementation("com.auth0:java-jwt:4.4.0")
    
    // Adventure (Paper bundles this, but we need it for compilation)
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    // Note: adventure-platform-bukkit not needed on Paper - it's bundled
    
    // GUI Library (1.49 for 1.21.8 support)
    implementation("xyz.xenondevs.invui:invui:1.49")
    implementation("xyz.xenondevs.invui:invui-kotlin:1.49")
    
    // Paper API (1.21.4 is forward-compatible with 1.21.8)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    
    // WorldEdit
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.10")
    
    // ProtocolLib (optional - for advanced features like sign input)
    compileOnly("com.github.dmulloy2:ProtocolLib:5.4.0")

    // MapEngine (optional - for map preview rendering)
    compileOnly("de.pianoman911:mapengine-api:1.8.11")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
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
    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
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
        println("📦 Deploying to: $pluginsFolder")
    }
    
    doLast {
        println("✅ JAR deployed!")
        
        // Auto-reload if server is running in tmux
        val reloadScript = file("scripts/reload-plugin.sh")
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