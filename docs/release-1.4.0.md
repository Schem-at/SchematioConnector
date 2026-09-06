# SchematioConnector 1.4.0

Axiom 6.0.5 now has a Schematio library tool on every supported Fabric target:
Minecraft 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1 and 26.2. Search for a build and
load it into Axiom's clipboard, then use Axiom's normal placement and undo tools.
The Axiom jar is unchanged and is not bundled.

Downloads and previews use bounded queues and cancellable work. Transfers check
that the destination still belongs to the same world, clipboard or placement
before applying a result. The upload preview and submission use one frozen source
until you choose Refresh source. Closing a panel releases its pending work.

This release bundles panel-lib 0.1.3. Its toolbar starts with your mods; layout
controls are under the window icon. It preserves other editors' input callbacks
and restores the game viewport when switching back from Axiom.

Litematica v7 files now import into WorldEdit and FAWE through the bundled native
converter, including multiple regions and negative coordinates. Minecraft 1.21.11
is tested with Litematica 0.26.16 and MaLiLib 0.27.20; Connector accepts their newer
block-entity data representation. An older MaLiLib pin failed during packaged
client startup, which is why this release updates the pair.

## Installation

Download the Fabric jar with your Minecraft version in its filename from
[GitHub Releases](https://github.com/Schem-at/SchematioConnector/releases/tag/v1.4.0).
Install Fabric API and Fabric Language Kotlin 1.13.12 or newer. Axiom, Litematica
and WorldEdit are optional; panel-lib is included. Press K to open Schematio.

For Paper, install `SchematioConnector-Paper-1.4.0.jar` in `plugins/`. Install
WorldEdit for clipboard operations and set your community token with
`schematio settoken` in the server console. Java 21 is used for the 1.21.x targets;
Minecraft 26.x uses Java 25. WorldEdit 7.4.x also requires Java 25.

## Validation

Builds and unit tests, packaged startup and in-game checks are separate checks:

- Core: 629 tests. Fabric: 52 tests per target. panel-lib: 12 tests per target.
- Packaged startup: six Fabric servers and six Paper servers with WorldEdit.
- Packaged clients: all six targets with optional editors absent, then with
  Axiom 6.0.5 and the pinned Litematica/MaLiLib pair. Each case captures four real
  schematic renders, including transparent PNGs. The Axiom cases also check tool
  registration, schematic preparation and preservation of the existing clipboard.
- Paper clipboard tests: real Litematica v7 import and paste on all six server
  targets, with directional blocks and a chest across two regions.
- Full mod/plugin bridge flows: all six Fabric clients joined their matching
  Paper server through MC-Inspector. Signed handshakes, load without a world edit,
  explicit paste, clipboard draft upload, Litematica export, chest preview and
  fresh verification after reconnect all passed. These used development clients
  and a local backend; the packaged-client checks above test the shipped jars.
- Detailed Axiom placement, cancellation and repeated editor handoffs on 26.2
  are recorded in [the integration evidence](editor-integration.md).

`python3 scripts/validate.py --jdk21 /path/to/jdk21 --jdk25 /path/to/jdk25 --clients`
repeats the matrix. Gradle reuses unchanged work; server downloads are shared and
server cases run with three workers. The initial warm build took 41 seconds;
individual packaged client cases took about 12–21 seconds on the test Mac.
Logs and machine-readable results are kept under `build/release-readiness/`.

The runtime checks use isolated worlds and an offline test identity. They do not
establish a successful production account upload. The production bridge public
key endpoint is checked before publishing.

## Limits and disclosure

Direct Axiom integration recognizes version 6.0.5. Imports are limited to 16 MiB
and two million blocks in volume. Files containing entities are rejected because
the inspected Axiom schematic loader does not preserve them in its placement
list. Other Axiom versions can use the downloaded file through normal import.

The client contacts Schematio for sign-in, browsing and transfers. Uploads send
the schematic, thumbnail and details you submit. See the README's network-use
section for the authentication and server bridge data flow.

Connector was originally handwritten. Development now uses AI assistance for code,
tests and documentation, including this release text. Source changes and issue
reports are public.
