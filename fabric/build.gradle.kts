plugins {
    id("fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

// Version properties from root gradle.properties
val fabricMinecraftVersion: String = rootProject.extra.get("fabricMinecraftVersion") as String
val fabricLoaderVersion: String = rootProject.extra.get("fabricLoaderVersion") as String
val fabricApiVersion: String = rootProject.extra.get("fabricApiVersion") as String
val fabricYarnMappings: String = rootProject.extra.get("fabricYarnMappings") as String
val worldeditVersion: String = rootProject.extra.get("worldeditVersion") as String

base {
    archivesName.set("SchematioConnector-Fabric-mc$fabricMinecraftVersion")
}

repositories {
    maven("https://maven.fabricmc.net/") {
        name = "Fabric"
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "EngineHub"
    }
}

dependencies {
    // Core module
    implementation(project(":core"))

    // Minecraft & Fabric
    minecraft("com.mojang:minecraft:$fabricMinecraftVersion")
    mappings("net.fabricmc:yarn:$fabricYarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Fabric API modules we need
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Fabric Language Kotlin for Kotlin entrypoints
    modImplementation("net.fabricmc:fabric-language-kotlin:1.13.0+kotlin.2.1.0")

    // WorldEdit core API (optional - for clipboard operations when WorldEdit is installed)
    // Uses worldedit-core directly to avoid Loom version mismatch with worldedit-fabric
    compileOnly("com.sk89q.worldedit:worldedit-core:$worldeditVersion") {
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
        exclude(group = "org.apache.logging.log4j")
    }

    // HTTP dependencies (needed at compile time and runtime)
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpcore:4.4.16")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")

    // Include core module and its dependencies in the JAR
    include(project(":core"))

    // Core module dependencies that need to be bundled
    include("com.google.code.gson:gson:2.11.0")
    include("org.apache.httpcomponents:httpclient:4.5.14")
    include("org.apache.httpcomponents:httpcore:4.4.16")
    include("org.apache.httpcomponents:httpmime:4.5.14")
    include("com.auth0:java-jwt:4.4.0")
    include("commons-logging:commons-logging:1.2")
    include("commons-codec:commons-codec:1.15")
}

loom {
    // Disable Javadoc generation from mod dependencies to avoid namespace issues with newer Fabric API
    // This fixes the "Javadoc provided by mod must have an intermediary source namespace" error in 1.21.11+
    @Suppress("UnstableApiUsage")
    enableModProvidedJavadoc.set(false)

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
        }
        named("server") {
            server()
            configName = "Fabric Server"
            ideConfigGenerated(true)
            runDir("run")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", fabricMinecraftVersion)
    inputs.property("loader_version", fabricLoaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to fabricMinecraftVersion,
            "loader_version" to fabricLoaderVersion
        )
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
    }
}

// Ensure we're using Java 21
java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
