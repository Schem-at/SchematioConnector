# SchematioConnector 1.3.3 release checks

Candidate branch: `release-readiness-1.3.3`. Results recorded on 5 September 2026.
Publication remains pending: production has no attestation key, and the cached
Minecraft login has expired. Authenticated player flows and article visual review
still need to finish.

## Automated results

| Check | Result | Scope |
| --- | --- | --- |
| Shared core tests | Pass | 627 tests |
| Paper tests | Pass | 36 tests, including bridge round trips and reconnect races |
| Fabric tests | Pass | 46 tests per Minecraft target; 939 JVM tests in total |
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

The [Build run on 5 September](https://github.com/Schem-at/SchematioConnector/actions/runs/33951712737)
passed all twelve packaged servers. The Build workflow repeats these checks for
each candidate update.
Server startup checks include mod/plugin loading, `/schematio info`, and shutdown;
they do not connect a player. Fabric server checks use required dependencies only.
Paper checks include WorldEdit; a separate Paper 1.21.8 check also passed without it.
Minecraft 1.21.x checks use Java 21 and WorldEdit 7.3.19; 26.x checks use Java 25
and WorldEdit 7.4.5.

## In-game results

MC-Inspector drove a real Fabric client connected to Paper with WorldEdit on all
six targets. Every version passed these checks and both processes exited cleanly:

- Verify the backend's signed server attestation.
- Open the mod's browser through the server's `/schematio` command.
- Load a schematic reference into WorldEdit without changing the world.
- Paste explicitly and check stone plus a directional oak log in the world.
- Upload the WorldEdit clipboard and receive a draft identifier.
- Create a Litematica placement, export it, and check the original block states.
- Render a chest from schematic bytes and read back a nonempty PNG with alpha.
- Disconnect, clear trust, reconnect with a new nonce, and verify a new attestation.

The bridge matrix uses a localhost HTTP fixture with an ephemeral Ed25519 key.
It validates the uploaded schematic's block contents. It does **not** establish
Mojang authentication or production Schematio permissions; the backend's separate
43-test suite covers its attestation and clipboard endpoints.

The 26.2 preview composer passed projection and view presets, FOV adjustment,
scroll zoom, orbit, pan, reframe, and transparent capture through its UI in a
joined world. Capture produced a valid 1280×720 PNG. The controls check fixed a
clipped FOV label and made Top preserve the selected projection.
The [in-game results](evidence/1.3.3/in-game.json),
[26.2 composer PNG](evidence/1.3.3/composer-26.2.png), and
[1.21.8 chest PNG](evidence/1.3.3/chest-1.21.8.png) are saved with this report.

File previews instantiate default block entities; the current
Nucleation wrapper does not expose their custom NBT, so sign text and other custom
block-entity data are not represented in these previews.

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

For the bridge matrix, start `scripts/BridgeBackend.java` with the bundled
Nucleation jar and Gson on its classpath. Populate Paper server caches with
`smoke-server.py`, then run:

```sh
python3 scripts/smoke-bridge.py 1.21.8 1.21.9 1.21.10 1.21.11 26.1 26.2 \
  --inspector-root /path/to/MC-Inspector --jdk21 /path/to/jdk21 --jdk25 /path/to/jdk25
```

The inspector jars must match each Minecraft version. MC-Inspector needed its
26.1 presentation hook updated and its 26.2 toast API adjusted for these checks;
those edits are in the local MC-Inspector checkout. Logs and machine-readable
results from the final run are in `build/release-readiness/final-bridge/`, with
the combined result in `build/release-readiness/final-bridge-matrix.log`.

## Player and publication checks

- [x] Real Paper/Fabric connection: attestation, server command opening the mod,
  load into WorldEdit, explicit paste, clipboard upload, reconnect.
- [ ] Mojang sign-in and authenticated browsing against Schematio.
- [x] Litematica load/placement/export on all six versions.
- [x] Block-entity previews on all six versions; composer controls and capture on 26.2.
- [ ] Authenticated local-file upload through the UI.
- [ ] Production signing key and authenticated bridge round trip. See
  [production-bridge-setup.md](production-bridge-setup.md).
- [x] Local article visual review in Schematio, including mobile layout; all seven
  hosted candidate downloads return the CI-verified bytes.
- [ ] Deploy shared article downloads and ingest the seven jars on production.

The article source is [article/index.md](article/index.md). Its Pagina bundle was
built, verified, and imported as a local Schematio draft. No public release or
article has been published. The revised article has been reviewed in Chrome on the local instance.

The illustrated draft now includes six real game captures, four responsive
Kineglyph illustrations, a command typing video, hosted candidate jars, and the example build. See
[article-capture-notes.md](article-capture-notes.md) for the additional 26.2
singleplayer and local-backend checks, capture provenance, and the newly observed
private-preview authentication issue.
