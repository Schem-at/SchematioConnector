# Releasing SchematioConnector

How a release is versioned, built, published to GitHub Releases, and - once enabled - published to Modrinth.

## Versioning

The project follows [Semantic Versioning](https://semver.org). The version is defined in the root `gradle.properties`:

```properties
versionMajor=1
versionMinor=1
versionPatch=1
```

Bump **MAJOR** for breaking changes, **MINOR** for new features, **PATCH** for bug fixes. The same version is used by every artifact; the Fabric jars additionally encode the Minecraft version in the file name.

## Release artifacts

One release produces **6 jars** (1 Paper plugin + 5 Fabric mod jars):

| Artifact | Built by | Output |
|---|---|---|
| Paper plugin (1 jar, version-agnostic) | `./gradlew :bukkit:build` | `bukkit/build/libs/SchematioConnector-Paper-<version>.jar` |
| Fabric mod (1 jar **per** MC version: 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1) | `./gradlew :fabric:buildAllVersions` | `build/libs/<version>/SchematioConnector-Fabric-mc<mc>-<version>.jar` |

The Fabric version list lives in `buildableVersions` in `fabric/stonecutter.gradle.kts`; the release workflow's `EXPECTED_JAR_COUNT` (currently 6) must match.

## Cutting a release

1. Bump `versionMajor` / `versionMinor` / `versionPatch` in `gradle.properties` and commit.
2. Sanity-check locally:

   ```bash
   ./gradlew :core:test :bukkit:build :fabric:buildAllVersions
   ls build/libs/<version>/   # one fabric jar per MC version
   ```

3. Create an **annotated** tag - the annotation body becomes the release notes:

   ```bash
   git tag -a v1.2.0 -m "v1.2.0

   - Added X
   - Fixed Y"
   git push origin v1.2.0
   ```

4. The `Release` workflow (`.github/workflows/release.yml`) triggers on `v*` tag pushes and:
   - builds the Paper plugin jar (`:core:build :bukkit:build`, runs `:core:test`),
   - builds the Fabric mod jar for every supported Minecraft version,
   - collects everything and creates a **GitHub Release** named `SchematioConnector v<version>` with the tag annotation as the body and all jars attached.

5. Verify the release page lists the full artifact set: **1 Paper jar + 1 Fabric jar per supported MC version** (6 jars total).

(`.github/workflows/build.yml` is the per-push/PR CI counterpart - it builds but does not release.)

## Modrinth publishing - wired but INACTIVE

The workflow contains Modrinth publish steps for **both** the Fabric mod (one version entry per MC version - 1.21.8 through 1.21.11 and 26.1 - with correct `game_versions`, `loaders`, and dependency metadata) and the Paper plugin (`loaders: paper`). They are fully configured but deliberately **inert**: they are gated on the `MODRINTH_TOKEN` secret being present (`if: ${{ secrets.MODRINTH_TOKEN != '' }}`) and on a `publish_modrinth` flag that defaults to `false`. With no secret set, the steps are no-ops on every release.

### Going live - the exact switches

1. Create the two Modrinth projects (mod + plugin) and note their project IDs.
2. In the GitHub repo settings:

   | Where | Name | Value |
   |---|---|---|
   | **Secrets** → Actions | `MODRINTH_TOKEN` | A Modrinth personal access token with permission to create versions |
   | **Variables** → Actions | `MODRINTH_MOD_PROJECT_ID` | Modrinth project ID of the **Fabric mod** |
   | **Variables** → Actions | `MODRINTH_PLUGIN_PROJECT_ID` | Modrinth project ID of the **Paper plugin** |

3. Flip the `publish_modrinth` flag to `true` (the workflow input / release flag whose default is `false` in `release.yml`).

That's the entire activation: add the secret, set the two variables, flip the flag. The changelog is taken from the release body, so no extra metadata work is needed per release.

To take Modrinth publishing back offline, remove the `MODRINTH_TOKEN` secret (or set `publish_modrinth` back to `false`).

## Build toolchain

| Tool | Version | Where it's pinned |
|---|---|---|
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Fabric Loom | 1.16-SNAPSHOT | root `build.gradle.kts` |
| Kotlin | 2.4.0 | root `build.gradle.kts` (matches `fabric-language-kotlin` 1.13.12) |
| Stonecutter | 0.9.5 | `settings.gradle.kts` |
| Java | 25 for the Gradle daemon (required since 26.1 targets Java 25); compile toolchains: 21 for 1.21.x, 25 for 26.x (switch automatically) | `gradle/gradle-daemon-jvm.properties`, `fabric/build.gradle.kts` |

Shared dependency pins (Fabric Loader, Fabric Language Kotlin, conditional-mixin, WorldEdit) live in the root `gradle.properties`. **Per-Minecraft-version pins** (Fabric API, Litematica, MaLiLib Modrinth version IDs, the `fabric.mod.json` MC predicate) live in `fabric/versions/<version>/gradle.properties` - update these when bumping a version's dependencies, and add a new directory there when adding a Minecraft version (see the README's *Adding a new Minecraft version* section).

All Fabric versions build on **official Mojang mappings**; 26.x builds run loom in no-remap mode (`fabric.loom.disableObfuscation=true`) because MC 26.x ships unobfuscated.
