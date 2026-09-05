# SchematioConnector 1.3.3 release checks

Candidate branch: `release-readiness-1.3.3`. Results recorded on 5 September 2026.
Publication remains pending until the player flows below have been checked.

## Automated results

| Check | Result | Scope |
| --- | --- | --- |
| Shared core tests | Pass | 627 tests |
| Paper tests | Pass | 36 tests, including bridge round trips and reconnect races |
| Fabric tests | Pass | 45 tests per Minecraft target |
| Release packaging | Pass | Seven jars, metadata, entrypoints, licenses, nested dependencies, five native platforms, SHA-256 manifest |
| Native parser | Pass | Parse/write/diff using the shipped wrapper on Windows x64, Linux x64/arm64, macOS x64/arm64 |
| Schematio backend bridge tests | Pass | 43 tests, 188 assertions: attestation, keys, clipboard resolve and drafts |

All six development clients passed real model rendering and clean shutdown on
macOS arm64 with their pinned Litematica/MaLiLib dependencies. Each rendered stone,
a directional oak log, glass and water in isometric and perspective views; PNG
checks cover both studio and transparent backgrounds. Saved PNGs were inspected.
This does not establish rendering on other GPUs or operating systems.

| Minecraft | Fabric dedicated server | Paper with WorldEdit | Client preview/shutdown |
| --- | --- | --- | --- |
| 1.21.8 | Pass | Pass | Pass |
| 1.21.9 | Pass | Pass | Pass |
| 1.21.10 | Pass | Pass | Pass |
| 1.21.11 | Pass | Pass | Pass |
| 26.1 | Pass | Pass on Paper 26.1.2 | Pass |
| 26.2 | Pass | Pass | Pass |

The initial packaged server matrix passed before the final bridge and renderer
fixes. CI reruns all twelve packaged servers against the final candidate jars.
Server startup checks include mod/plugin loading, `/schematio info`, and shutdown;
they do not connect a player. Fabric server checks use required dependencies only.
Paper checks include WorldEdit; a separate Paper 1.21.8 check also passed without it.

## Reproduce

```sh
./gradlew :core:test :bukkit:build :fabric:buildAllVersions
python3 scripts/verify-release.py
python3 scripts/smoke-client.py 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.2 --jdk /path/to/jdk21
python3 scripts/smoke-server.py paper 1.21.11 --worldedit \
  --jar bukkit/build/libs/SchematioConnector-Paper-1.3.3.jar --java /path/to/jdk21/bin/java
```

Client checks require a desktop/OpenGL session. They use isolated game directories
under `build/release-readiness/clients/` with DevAuth disabled. Reports, screenshots,
and logs go under `build/release-readiness/`; server inputs record dependency pins
and artifact hashes. Native build provenance is in [nucleation-build.md](nucleation-build.md).

MC-Inspector can be added to an interactive test client without bundling it into
the release:

```sh
./gradlew :fabric:1.21.11:runClient -I scripts/client-smoke.init.gradle \
  -Dschematio.smoke.interactive=true -Dschematio.inspector.root=/path/to/MC-Inspector
python3 scripts/inspector-call.py get_state
```

## Player and publication checks

- [ ] Real Paper/Fabric connection: attestation, server command opening the mod,
  load into WorldEdit, explicit paste, clipboard upload, reconnect.
- [ ] Mojang sign-in and authenticated browsing against Schematio.
- [ ] Litematica load/placement and local file upload through the UI.
- [ ] Preview composer controls and block entities in a joined world.
- [ ] Article visual review in Schematio; final download links return all seven jars.

The article source is [article/index.md](article/index.md). Its Pagina bundle was
built, verified, and imported as a local Schematio draft. No public release or
article has been published. The browser runtime was unavailable in this session.
