plugins {
    // Kotlin >= 2.3.0 is required to compile the MC 26.1 fabric variant:
    // MC 26.x ships Java 25 class files (major 69) and JVM target 25 support
    // first landed in Kotlin 2.3.0. 2.4.0 matches fabric-language-kotlin 1.13.12.
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.kapt") version "2.4.0" apply false
    id("com.gradleup.shadow") version "8.3.8" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
    // Loom 1.16 is required: current Fabric API / Litematica builds are produced
    // with Loom 1.16.x, and 1.16 handles the 26.x mojmap-only scheme.
    id("fabric-loom") version "1.16-SNAPSHOT" apply false
}

// Version from gradle.properties (semantic versioning)
val versionMajor: String by project
val versionMinor: String by project
val versionPatch: String by project

// Bukkit version properties
val bukkitMinVersion: String = project.findProperty("bukkit_min_version")?.toString() ?: "1.21.8"
val bukkitTargetVersion: String = project.findProperty("bukkit_target_version")?.toString() ?: "1.21.8"
val bukkitApiVersion: String = project.findProperty("bukkit_api_version")?.toString() ?: "1.21"

// Fabric versions are managed by Stonecutter: the MC version list lives in
// settings.gradle.kts, per-version pins in fabric/versions/<ver>/gradle.properties,
// and shared pins (loader/FLK/conditional-mixin) in gradle.properties.

// WorldEdit version
val worldeditVersion: String = project.findProperty("worldedit_version")?.toString() ?: "7.3.10"

// Export properties for subprojects
extra["bukkitMinVersion"] = bukkitMinVersion
extra["bukkitTargetVersion"] = bukkitTargetVersion
extra["bukkitApiVersion"] = bukkitApiVersion
extra["worldeditVersion"] = worldeditVersion

allprojects {
    group = "io.schemat"
    version = "$versionMajor.$versionMinor.$versionPatch"

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
            content { includeGroupByRegex("com\\.github\\..*") }
        }
        maven("https://repo.codemc.io/repository/maven-public/") {
            name = "codemc"
        }
        maven("https://maven.fabricmc.net/") {
            name = "fabric"
        }
    }
}

// Configure non-Fabric subprojects (core and bukkit).
// Match by path: the Stonecutter-versioned fabric subprojects are named after
// MC versions (:fabric:1.21.8 etc.) and must NOT receive this configuration.
configure(subprojects.filter { it.path == ":core" || it.path == ":bukkit" }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    val targetJavaVersion = 21
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(targetJavaVersion)
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        // Kotlin stdlib for all modules
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

        // Testing
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
        testImplementation("io.mockk:mockk:1.13.8")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
