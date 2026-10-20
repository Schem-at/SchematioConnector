# SchematioConnector 1.5.0

Adds Minecraft 26.3. There is a new Fabric jar for 26.3, and the Paper plugin runs
on Paper 26.3 unchanged.

Minecraft 26.3 replaced GLFW with SDL3 and moved its render backend to a new package.
Connector's keybinds now use Minecraft's own key constants, and the offscreen preview
renderer draws through 26.3's render passes. Previews, snapshot thumbnails and the
HDRI background render the same way they do on 26.2.

This release bundles panel-lib 0.1.6, which handles SDL3 input for the Schematio
panels. On 26.3, detached panel windows are not available; panels stay inside the
game window. Other Minecraft versions are unchanged.

Axiom integration on 26.3 recognizes Axiom 6.1.3. Axiom 6.0.5 stays the recognized
version on 1.21.8 through 26.2. Axiom 6.1.3 crashes at startup on Fabric Loader
0.19.3, whose MixinExtras cannot read one of Axiom's mixins. Use Fabric Loader 0.19.5
or newer with Axiom on 26.3. Connector itself still works on older loaders.

## Installation

Download the Fabric jar with your Minecraft version in its filename from
[GitHub Releases](https://github.com/Schem-at/SchematioConnector/releases/tag/v1.5.0).
Install Fabric API and Fabric Language Kotlin 1.13.12 or newer. Axiom, Litematica
and WorldEdit are optional; panel-lib is included. Press K to open Schematio.

On 26.3 the tested optional mods are Axiom 6.1.3, Litematica 0.29.1, MaLiLib 0.30.2
and WorldEdit 7.4.6.

For Paper, install `SchematioConnector-Paper-1.5.0.jar` in `plugins/`. Install
WorldEdit for clipboard operations and set your community token with
`schematio settoken` in the server console. Java 21 is used for the 1.21.x targets;
Minecraft 26.x uses Java 25. WorldEdit 7.4.x also requires Java 25.

## Validation

`scripts/validate.py --clients` passed all 20 checks for 1.5.0 on the seven targets
(1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.2 and 26.3):

- Core: 629 tests. Fabric: 52 tests per target. panel-lib: 15 tests per target.
- All eight release jars passed packaging checks.
- Packaged startup: seven Fabric servers and seven Paper servers with WorldEdit.
- Packaged clients: all seven targets with optional editors absent, then with Axiom
  and the pinned Litematica/MaLiLib pair. Each case captures four real schematic
  renders, including transparent PNGs. On every target the Axiom case checks tool
  registration, schematic preparation and preservation of the existing clipboard.
- Paper clipboard tests: real Litematica v7 import and paste on all seven server
  targets.

The full mod/plugin bridge flows through MC-Inspector and Axiom placement by hand
were not repeated for 1.5.0. On 26.3, scroll direction, IME text input and
borderless fullscreen have not been checked by hand yet.

The runtime checks use isolated worlds and an offline test identity. They do not
establish a successful production account upload. The production bridge public
key endpoint is checked before publishing.

## Limits and disclosure

Direct Axiom integration recognizes Axiom 6.0.5, or 6.1.3 on Minecraft 26.3.
Imports are limited to 16 MiB and two million blocks in volume. Files containing
entities are rejected. Other Axiom versions can use the downloaded file through
normal import.

The client contacts Schematio for sign-in, browsing and transfers. Uploads send
the schematic, thumbnail and details you submit. See the README's network-use
section for the authentication and server bridge data flow.

Connector was originally handwritten. Development now uses AI assistance for code,
tests and documentation, including this release text. Source changes and issue
reports are public.
