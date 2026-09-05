# Releasing SchematioConnector

A release contains one Paper plugin, six Fabric mods, and `SHA256SUMS`. Fabric
jars target Minecraft 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, and 26.2.

## Prepare and verify

1. Update `versionMajor`, `versionMinor`, and `versionPatch` in `gradle.properties`.
2. With JDK 21 and JDK 25 installed, run:

   ```sh
   ./gradlew :core:test :bukkit:build :fabric:buildAllVersions
   python3 scripts/verify-release.py
   ```

3. Run the client checks and remaining player flows in
   [docs/release-readiness.md](docs/release-readiness.md). Record results against
   the candidate commit and artifact hashes. Builds, unit tests, and server
   startup checks do not verify a player's complete upload/download flow.
4. Open a PR. The Build workflow checks jar metadata, dependencies, licenses, and
   the exact artifact set. Its twelve runtime jobs start the packaged Fabric mod
   on each target and the Paper plugin with WorldEdit on each listed Paper version.
5. Check that the article's download filenames match the candidate. Keep it as a
   draft until the release exists and every link returns a file.
6. Run `python3 scripts/check-backend.py` against production and complete an
   authenticated bridge round trip. If no signing key is configured, follow
   [docs/production-bridge-setup.md](docs/production-bridge-setup.md). The Release
   workflow requires a usable public key before publishing.

Fabric outputs go to `build/libs/<version>/`; Paper goes to `bukkit/build/libs/`.
`build/release-readiness/artifacts.json` records hashes and packaging results.

## Publish to GitHub

After the release checks pass, merge the PR and create an annotated tag. Pushing
that tag publishes the release once its build and packaged runtime checks pass.
The tag must match `gradle.properties`; a mismatch fails the workflow.

```sh
git tag -a v1.3.3 -F docs/release-notes-1.3.3.md
git push origin v1.3.3
```

The annotation's first line is its subject; the remaining body becomes the GitHub
release notes. Verify seven jars and `SHA256SUMS` on the release page. Download
them into a clean directory and run `sha256sum -c SHA256SUMS` (or
`shasum -a 256 -c SHA256SUMS` on macOS).

The Release workflow also accepts an existing tag through `workflow_dispatch`.
It checks out that tag for both the build and the server tests.

## Article

The Pagina article source is in `docs/article/`. Build and pack it with the Pagina
CLI, then verify the bundle before importing it into Schematio. The source sets
`status: draft`. Check the rendered page and release links before publishing it.
Remove the candidate notice when the release is public.

## Modrinth

Modrinth publication requires two project IDs (`MODRINTH_MOD_PROJECT_ID` and
`MODRINTH_PLUGIN_PROJECT_ID` repository variables) and a `MODRINTH_TOKEN` secret.
Set `publish_modrinth=true` on a Release workflow dispatch to publish. Tag pushes
do not publish to Modrinth. Each Fabric entry lists its Minecraft version, Fabric
API and Fabric Language Kotlin dependencies; the Paper entry lists WorldEdit as
optional.

## Dependency updates

Shared pins live in `gradle.properties`. Per-version Fabric API, Litematica,
MaLiLib, and Minecraft predicates live in `fabric/versions/<version>/gradle.properties`.
When adding a target, update `settings.gradle.kts`, `buildableVersions`, the
server smoke matrix, release jar count, and Modrinth entries together.

See [docs/nucleation-build.md](docs/nucleation-build.md) for the native parser
bundle. The no-ai-slop skill in `.agents/skills/no-ai-slop/` applies to release
notes, the article, and installation instructions.
