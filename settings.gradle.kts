pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.kikugie.dev/releases") {
            name = "KikuGie Releases"
        }
        maven("https://maven.kikugie.dev/snapshots") {
            name = "KikuGie Snapshots"
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.5"
}

rootProject.name = "SchematioConnector"

include(":core")
include(":bukkit")

// The fabric module is multi-version via Stonecutter: one subproject per Minecraft version.
// Per-version dependency pins live in fabric/versions/<version>/gradle.properties.
// The active development version is set in fabric/stonecutter.gradle.kts.
stonecutter {
    create(":fabric") {
        versions("1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1")
        vcsVersion = "1.21.11"
    }
}
