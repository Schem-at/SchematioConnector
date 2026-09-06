plugins {
    id("fabric-loom") version "1.16.3"
}

// Packaged-client checks use normal Fabric discovery, including each mod's nested jars.
val pocRuntime by configurations.creating { isTransitive = false }

group = "io.schemat"
version = "0.1.0-poc.1"
base.archivesName = "Schematio-Axiom-mc26.2"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
    mavenLocal()
    maven("https://nano112.github.io/panel-lib/maven/") { content { includeGroup("dev.harrison") } }
}

val axiomCompile by configurations.creating { isTransitive = false }
val extractAxiomApi by tasks.registering(Copy::class) {
    from(provider { zipTree(axiomCompile.singleFile) })
    include("META-INF/jars/axiomclientapi-unobf.jar")
    eachFile { path = name }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("axiom-api"))
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.155.2+26.2")
    pocRuntime("net.fabricmc.fabric-api:fabric-api:0.155.2+26.2")
    axiomCompile("maven.modrinth:axiom:o59cWLPI")
    compileOnly(files(axiomCompile))
    compileOnly(files(layout.buildDirectory.file("axiom-api/axiomclientapi-unobf.jar")).builtBy(extractAxiomApi))
    // These are development dependencies only. The output jar contains our classes/resources alone.
    if (providers.gradleProperty("withAxiom").orNull == "true") {
        runtimeOnly(files(axiomCompile))
        pocRuntime("maven.modrinth:axiom:o59cWLPI")
    }
    providers.gradleProperty("inspectorJar").orNull?.let {
        runtimeOnly(files(it))
        runtimeOnly("net.fabricmc:fabric-language-kotlin:1.13.12+kotlin.2.4.0")
        runtimeOnly("org.mozilla:rhino:1.7.15")
        runtimeOnly("dev.harrison:panel-lib-mc26.2:0.1.1")
        pocRuntime(files(it))
        pocRuntime("net.fabricmc:fabric-language-kotlin:1.13.12+kotlin.2.4.0")
        pocRuntime("dev.harrison:panel-lib-mc26.2:0.1.1")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runPocClient") {
    mods.from(pocRuntime)
    runDir = layout.projectDirectory.dir(providers.gradleProperty("pocRunDir").getOrElse("run"))
    jvmArgs.addAll("-Xmx3G", "-XX:ActiveProcessorCount=4")
    programArgs.addAll("--username", "SchematioPOC", "--accessToken", "0", "--version", "26.2", "--width", "1440", "--height", "900")
    providers.gradleProperty("pocWorld").orNull?.let { programArgs.addAll("--quickPlaySingleplayer", it) }
    providers.gradleProperty("pocEndpoint").orNull?.let { jvmArgs.add("-Dschematio.axiom.endpoint=$it") }
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25); withSourcesJar() }
tasks.test { useJUnitPlatform() }
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
loom {
    runs.named("client") {
        client()
        runDir("run")
        vmArg("-Xmx3G")
        vmArg("-XX:ActiveProcessorCount=4")
    }
}
