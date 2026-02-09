plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
    id("com.gradleup.shadow") version "8.3.5" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
    id("fabric-loom") version "1.13-SNAPSHOT" apply false
}

// Version from gradle.properties (semantic versioning)
val versionMajor: String by project
val versionMinor: String by project
val versionPatch: String by project

// Bukkit version properties
val bukkitMinVersion: String = project.findProperty("bukkit_min_version")?.toString() ?: "1.21.8"
val bukkitTargetVersion: String = project.findProperty("bukkit_target_version")?.toString() ?: "1.21.8"
val bukkitApiVersion: String = project.findProperty("bukkit_api_version")?.toString() ?: "1.21"

// Fabric version properties
val fabricMinecraftVersion: String = project.findProperty("fabric_minecraft_version")?.toString() ?: "1.21.8"
val fabricLoaderVersion: String = project.findProperty("fabric_loader_version")?.toString() ?: "0.16.14"
val fabricApiVersion: String = project.findProperty("fabric_api_version")?.toString() ?: "0.136.0+1.21.8"
val fabricYarnMappings: String = project.findProperty("fabric_yarn_mappings")?.toString() ?: "1.21.8+build.1"

// WorldEdit version
val worldeditVersion: String = project.findProperty("worldedit_version")?.toString() ?: "7.3.10"

// Export properties for subprojects
extra["bukkitMinVersion"] = bukkitMinVersion
extra["bukkitTargetVersion"] = bukkitTargetVersion
extra["bukkitApiVersion"] = bukkitApiVersion
extra["fabricMinecraftVersion"] = fabricMinecraftVersion
extra["fabricLoaderVersion"] = fabricLoaderVersion
extra["fabricApiVersion"] = fabricApiVersion
extra["fabricYarnMappings"] = fabricYarnMappings
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
        }
        maven("https://repo.codemc.io/repository/maven-public/") {
            name = "codemc"
        }
        maven("https://maven.fabricmc.net/") {
            name = "fabric"
        }
    }
}

// Configure non-Fabric subprojects (core and bukkit)
configure(subprojects.filter { it.name != "fabric" }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    val targetJavaVersion = 21
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(targetJavaVersion)
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        // Kotlin stdlib for all modules
        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

        // Testing
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
        testImplementation("io.mockk:mockk:1.13.8")
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
