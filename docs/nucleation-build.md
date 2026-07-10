# Bundling Nucleation (schematic parser / future VCS diff engine)

[Nucleation](https://github.com/Schemati/Nucleation) is a Rust library that parses
and iterates schematic files (litematic, schem, nbt, …) and exposes a `diff()` API.
We use it **only** for iterating schematic block data — meshing, model lookup and
rendering are done by the Minecraft client (see the native preview renderer). It is
shipped as a JNI fat-jar bundled into the Fabric mod jar-in-jar.

## What ships

`fabric/libs/nucleation-<version>.jar` is a fat-jar containing:

- `com/github/schemat/nucleation/{Schematic,Block,NativeLoader,NucleationNative}.class`
  — the Java wrapper. `Schematic.fromBytes(byte[])` auto-detects the format;
  iteration yields `Block(x, y, z, name, Map<String,String> properties)` where `name`
  + properties form a canonical Minecraft blockstate string.
- `native/<platform>/lib...` — the compiled JNI native(s). `NativeLoader` extracts the
  matching native from the classpath to a temp dir and `System.load`s it at runtime.

Currently bundled native: **macOS arm64** (`native/macos-arm64/libnucleation_jvm.dylib`).
Linux/Windows natives are a follow-up (see below).

## How it is wired into the build

`fabric/build.gradle.kts`:

- A `flatDir` repository (`LocalLibs`) points at `${rootDir}/fabric/libs` so the local
  jar resolves as the coordinate `:nucleation:<version>`. This is required because
  Loom's `include` (jar-in-jar) needs a *module component with capabilities* — a raw
  `files(...)` dependency fails with "not a module component and has no capabilities".
  The path is `rootDir`-relative because `build.gradle.kts` is shared across the
  Stonecutter version subprojects, so `projectDir` would point at `fabric/versions/<v>`.
- `include(implementation(":nucleation:$nucleationVersion")!!)` puts it on the dev/runtime
  classpath and bundles it (Loom `include` is **non-transitive**; this jar is
  self-contained so nothing else needs listing).
- `nucleation_version` is set in the root `gradle.properties`.

Verify the bundle:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :fabric:1.21.11:remapJar --rerun-tasks
JAR=$(ls fabric/versions/1.21.11/build/libs/SchematioConnector-Fabric-mc1.21.11-*.jar | grep -v sources)
unzip -l "$JAR" | grep nucleation               # -> META-INF/jars/nucleation-<version>.jar
```

## Rebuilding the native + jar

Source: `/Users/harrison/RustroverProjects/Nucleation/` (Cargo workspace; the JVM
binding is `nucleation-jvm/`, cdylib `name = "nucleation_jvm"`).

macOS arm64 (local):

```bash
cd /Users/harrison/RustroverProjects/Nucleation/nucleation-jvm
cargo build --release                                   # -> ../target/release/libnucleation_jvm.dylib
cd jvm
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew jar  # collectNatives copies the dylib in
# -> jvm/build/libs/nucleation-<nucleationVersion>.jar   (nucleationVersion in jvm/gradle.properties)
```

Then copy the jar into this repo and bump `nucleation_version` in `gradle.properties`:

```bash
cp jvm/build/libs/nucleation-<version>.jar \
   /Users/harrison/IdeaProjects/SchematioConnector/fabric/libs/
```

## Cross-platform (follow-up)

A multi-platform fat-jar (macOS x64/arm64, Linux x64/arm64, Windows x64) needs each
native built on/for its target. Nucleation provides `nucleation-jvm/build-cross.sh`;
the Windows `.dll` requires CI or a cross/Docker toolchain. Until then the mod's preview
renderer only functions on macOS arm64 — guard the call sites so other platforms degrade
gracefully (fall back to the placeholder thumbnail) rather than crashing on
`UnsatisfiedLinkError`.
