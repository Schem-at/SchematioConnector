# Litematica imports into WorldEdit: 1.3.4

Connector 1.3.3 rejects valid Litematica v7 files received by the Paper plugin.
The reported Redcraft log was reproduced locally on Paper 1.21.8 with FAWE 2.15.4
and the generated file in `scripts/fixtures/clipboard-v7.litematic`:

```text
Auto-detection unavailable (NoSuchMethodError), falling back to explicit formats
Format SPONGE_V3_SCHEMATIC failed: NoSuchElementException: No tag under the name 'Schematic' exists
Format SPONGE_V2_SCHEMATIC failed: IllegalStateException: Schematic is not version 2, but 7
Format MCEDIT_SCHEMATIC failed: IOException: Tag 'Schematic' does not exist or is not first
Failed to load schematic - no compatible format found
```

The file's version 7 belongs to Litematica. WorldEdit's Sponge and MCEdit readers
cannot read it. The bridge serves original files, and the website could also
return the original file with HTTP 200 after a requested conversion failed.
Using a namespaced command reaches the server but does not change those bytes.

1.3.4 uses the format-detection APIs shared by WorldEdit and FAWE. A single decoder
handles commands, bridge loads, and the Bukkit adapter. Files unsupported by the
installed WorldEdit readers pass through bundled Nucleation and then the Sponge
v3 reader. Invalid files still fail without replacing the player's clipboard.

Testing also found that Nucleation's region merge left its occupied bounds stale.
Conversion could then discard blocks outside the first region. The bundled native
libraries include `patches/nucleation-region-merge-bounds.patch`, which rebuilds
those bounds. The native workflow and the main build both test a multi-region
Litematica conversion.

The [website fix](https://github.com/Schem-at/schemati/pull/96) returns HTTP 503 and
a readable conversion error instead of sending a different file format. Failed
conversions do not consume quick-share uses or increment download counts.

## Verification, 6 September 2026

Build and unit tests: the Paper shadow JAR built; 627 core tests and 36 Bukkit tests
passed. The website passed 42 focused tests with 126 assertions.

Native tests: Linux x64/arm64, macOS x64/arm64, and Windows x64 passed parse, write,
diff, and multi-region conversion checks in
[native bundle run 33999205535](https://github.com/Schem-at/SchematioConnector/actions/runs/33999205535).
Those native files are bundled in the tested Paper JAR.

Packaged server tests used `scripts/smoke-clipboard.py`, with no native override.
Every row below loaded the Litematica v7 fixture, checked both regions and negative
coordinates, preserved a log's `axis=x` and a chest's name, round-tripped through
Sponge, rejected invalid data, and pasted the clipboard into a local world.

| Paper | WorldEdit implementation | Result |
| --- | --- | --- |
| 1.21.8 | WorldEdit 7.3.19 | Passed |
| 1.21.9 | WorldEdit 7.3.19 | Passed |
| 1.21.10 | WorldEdit 7.3.19 | Passed |
| 1.21.11 | WorldEdit 7.3.19 | Passed |
| 26.1.2 | WorldEdit 7.4.5 | Passed |
| 26.2 | WorldEdit 7.4.5 | Passed |
| 1.21.8 | FAWE 2.15.4 | Passed |
| 26.2 | FAWE 2.15.4 | Passed |

In-game test: MC-Inspector drove a Fabric 1.21.8 client against Paper 1.21.8 and
FAWE 2.15.4. The packaged plugin verified the fixture backend's signed attestation,
accepted original Litematica bytes through the bridge, left the world untouched
until `//paste`, and uploaded the resulting WorldEdit clipboard as a draft. Local
Litematica placement export, chest preview rendering, and reconnect attestation
also passed. Authentication used the local test backend.

The tested Paper JAR's SHA-256 is
`02d7587d609c8b47833ce0bbf43665c204d619576a05cd88afc2e9e600fae81a`.

## Reproduce

Use an isolated Paper cache downloaded by `scripts/smoke-server.py`:

```sh
python3 scripts/smoke-clipboard.py \
  --server-cache /path/to/cached-paper-server \
  --plugin bukkit/build/libs/SchematioConnector-Paper-1.3.4.jar \
  --worldedit /path/to/WorldEdit-or-FAWE.jar \
  --java-home /path/to/jdk \
  --name clipboard-check
```

To reproduce the old error, supply the 1.3.3 plugin and add
`--expect-litematic-failure`. To exercise the bridge, start `BridgeBackend.java`
with the fixture path and `litematic` as its second and third arguments. Run
`scripts/smoke-bridge.py` with `--fixture-x -2 --fixture-z -1` and the desired
`--plugin`, `--worldedit`, and single-version `--server-cache`.

Redcraft's public address reports a Velocity proxy. Its backend version and the
original failing file were not available during this test. Redcraft still needs
the updated plugin installed and the original download retried.
