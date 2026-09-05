# Bundled Nucleation parser

The Fabric jars include `fabric/libs/nucleation-0.2.19.jar`. Its Java wrapper reads
schematic files through JNI. Minecraft supplies the block models and rendering.

The jar contains natives for Linux x64/arm64, macOS x64/arm64, and Windows x64.
Each native passes `scripts/NativeSmoke.java` on its own operating system and
architecture, using the shipped Java wrapper. The test creates blocks, serializes
and reloads a schematic, and checks a changed-block diff.

## Rebuild

Run the **Nucleation native bundle** GitHub Actions workflow. It checks out
[Schem-at/Nucleation at 6d0bdd5](https://github.com/Schem-at/Nucleation/tree/6d0bdd58941925fc4d6d34c388c31c8b59854518)
and builds the JNI crate with:

```sh
cargo build --locked --release --no-default-features \
  --manifest-path native-source/nucleation-jvm/Cargo.toml
```

This revision preserves the `com.github.schemat.nucleation` JNI API. The current
upstream library uses a different binding API and cannot replace it without a
Connector migration. The wrapper reports version 0.2.19; the native reports 0.2.18.
`META-INF/NATIVE-SOURCE` records the source revision and build flags.

After all five platform jobs pass, download the `nucleation-multiplatform-jar`
artifact, copy its jar to `fabric/libs/`, and rebuild every Fabric target. Validate
the resulting release jars with `python3 scripts/verify-release.py`; it rejects
an incomplete native bundle.

The source jar is resolved through the build's `LocalLibs` repository and included
with Loom's `include` configuration. A raw `files(...)` dependency cannot supply
the module metadata Loom needs for jar-in-jar packaging.
