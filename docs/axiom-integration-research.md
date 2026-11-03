# Axiom integration research

Inspected on 6 September 2026. This is a study of the released jars, official
extension API, documentation, and Connector source. Integration is not implemented
or verified in game yet.

Axiom 6.0.5 is the latest release in the official Modrinth version feed, published
on 3 September 2026. It has builds for all six current Connector targets. The
installed PrismLauncher copies found during this investigation were 5.1.1.

| Connector target | Axiom release ID | Investigation |
| --- | --- | --- |
| Minecraft 1.21.8 | `LTHEWBiL` | Official release listing checked |
| Minecraft 1.21.9 | `LNCxTWoz` | Same jar as 1.21.10 |
| Minecraft 1.21.10 | `LNCxTWoz` | Jar downloaded, checksum verified, classes inspected |
| Minecraft 1.21.11 | `SAOZ8jry` | Official release listing checked |
| Minecraft 26.1 | `aZ3IOnv2` | Official release listing checked |
| Minecraft 26.2 | `o59cWLPI` | Jar downloaded, checksum verified, classes inspected |

Release availability does not establish compatibility with Connector. The 26.2
jar was used for the detailed method inspection; key integration signatures were
also checked in the 1.21.10 jar.

## What Connector can learn

### Make the build the object people work with

Axiom's Blueprint Browser keeps a thumbnail, name, authors, and tags together.
Its files carry a preview alongside the build, and its browser supports searching
inside folders. Opening a blueprint puts it in the clipboard; placement is a
separate step with a preview, transforms, confirmation, and cancellation.

For Connector, the useful lesson is to carry the selected build and its metadata
through browsing, previewing, downloading, and sharing. Show the available
destination beside the build. Preserve search and selection when returning from
a detail panel. Treat an upload source as a snapshot so the preview and submitted
file describe the same build even if the player edits their world meanwhile.

Sources: [Blueprint Browser](https://axiomdocs.moulberry.com/editor/windows/blueprints.html),
[clipboard](https://axiomdocs.moulberry.com/editor/windows/clipboard.html),
[placement](https://axiomdocs.moulberry.com/editor/placement.html).

### Budget expensive work and keep reusable results

The inspected `ChunkedBlockRegion` has chunk-level dirty tracking, reusable render
data, resource-pack invalidation, and separate handling for translucent sorting.
The Blueprint Browser uses a `FlowCache` whose inflow per tick is bounded, so a
large visible list does not initiate every missing thumbnail at once.

Connector already caches schematic geometry and skips unchanged preview frames
in `CachedSchematicMesh` and `SchematicRenderEngine`. Preserve that work. The next
useful experiments are bounded thumbnail scheduling and breaking initial mesh
construction into cancellable work with a frame budget. A camera orbit should
reuse geometry; a large source change should not monopolize the render thread.
Measure frame time, time to first preview, and retained CPU/GPU memory before
adopting a more complex cache. These are recommendations, not measured gains.

### Give input and rendering resources an explicit owner

Both inspected Axiom jars package ImGui under `imgui.moulberry92` and use a
separately named native library. Axiom's editor saves and restores its current
ImGui context around drawing, including the exceptional path. Its GLFW backend
retains previous callbacks and forwards cursor-enter events to the previous
handler for the main window.

Our `panel-lib` uses `imgui.*`. The distinct Axiom package and native filename
reduce direct binding collisions, but do not isolate the GLFW window, cursor,
OpenGL state, or Minecraft viewport.

Three concrete areas need work before coexistence can be declared reliable:

| Our code | Observation | Required behavior |
| --- | --- | --- |
| `panel-lib/.../framework/ImGuiManager.kt` | Installs a cursor-enter callback without retaining the previous callback; shutdown clears and frees the currently installed callback | Preserve other handlers and release only resources we own, or avoid replacing the callback |
| `panel-lib/.../framework/Overlay.kt` and input mixins | Focus decisions account for vanilla screens and our panels, without an Axiom ownership signal | Respect which editor owns input, including key releases, Escape, scroll, and text entry |
| `panel-lib/.../framework/GameViewport.kt` | Resizes and composites the game into our dock area; Axiom also manages its editor viewport | Allow one owner of game sizing; restore our own viewport before yielding |

These are conflicts identified by code inspection. They have not been reproduced
in a client containing both current builds. Axiom also intercepts input through
its own callbacks, so changing our mixin priority alone would not resolve the
ownership problem.

### Model capabilities separately from installation

Axiom distinguishes editor activation, server restrictions, and individual
permissions. Its blueprint import checks `CAN_IMPORT_BLOCKS`; the File menu also
checks that permission before enabling schematic import.

Connector should distinguish Axiom being installed, the adapter recognizing that
version, the current server allowing the operation, and the destination being
busy. Recheck world/session identity and permissions after an asynchronous
download. A Schematio server bridge handshake does not establish Axiom support
or permission to edit through Axiom.

## Integration boundary

The public [Axiom Client API](https://github.com/Moulberry/AxiomClientAPI) provides
custom-tool registration, block/boolean regions, tool pathers, raycasting, shared
executors, and render overrides. Its official example discovers services through
`ServiceLoader` and uses a compile-only dependency.

Neither inspected bundled API exposes a clipboard, blueprint catalog, selection
export, or schematic-import service. `ToolService.pushBlockRegionChange` submits
a world edit; it is inappropriate for a download action that should only prepare
a build for placement. Do not use its shared executors for Schematio network work
or shut them down from Connector.

The old extension example targets API `1.0.5.3`. The inspected 1.21.10 release
bundles API `1.0.33`; 26.2 bundles `axiomclientapi-unobf` `1.0.84`. Their rendering
contracts differ. Copying the example's dependencies or ImGui calls into a modern
integration would need review.

These internal entry points exist in both inspected releases:

| Operation | Internal entry point | Constraint |
| --- | --- | --- |
| Parse a Sponge schematic | `SchematicLoader.loadSponge(CompoundTag)` | Produces a clipboard object; not an official extension contract |
| Parse a Litematica file | `SchematicLoader.loadLitematic(CompoundTag)` | Reads regions and block entities; entity fidelity needs separate handling |
| Read current clipboard | `Clipboard.INSTANCE.getClipboard()` | Snapshot data without mutating the returned object |
| Replace clipboard | `Clipboard.INSTANCE.setClipboard(ClipboardObject)` | Raw setter does not perform the import permission check |
| Detect active placement | `Placement.INSTANCE.isPlacing()` | Never stop an existing placement as an incidental transfer step |
| Read/write native blueprints | `BlueprintIo.readRawBlueprint`, `createFromRaw`, `writeRaw` | Internal contract with version and resource-ownership concerns |
| Read editor state | `EditorUI.isEnabled()`, `isActive()` | Observation only; preserve the player's editor settings and tool state |

Minecraft classes are intermediary names in the shipped 1.21.10 jar and named
classes in 26.2. Reflection must resolve the runtime parameter types correctly;
matching only method names or using a development class name string is inadequate.
Public Java visibility is not a promise of API stability.

### Download into Axiom

The proposed player action is **Open in Axiom**, available when the optional
adapter recognizes the installed mod and the current operation is permitted.

1. Download and validate the build with bounded work and progress/cancellation.
2. Construct a complete temporary representation before touching Axiom's clipboard.
3. On the client thread, recheck the session, permissions, and whether the player
   changed the clipboard or started a placement during the download.
4. On a successful, still-current request, hand off the clipboard and release
   Connector's input/viewport ownership. Leave placement confirmation to Axiom.

Failures preserve the previous clipboard. A stale request must not overwrite a
new copy action. Loading must not invoke paste, select a different Axiom tool,
change its keybindings, or alter its undo history. Axiom owns the eventual edit
and its undo operation.

If an Axiom update changes the internal contract, disable the direct handoff and
retain **Save .schem**, with Axiom's normal File > Import Schematic workflow as a
fallback. Keep Axiom optional and do not bundle its jar or API implementations.

### Upload from Axiom

Add **Axiom clipboard** to Connector's existing source picker. Capture blocks,
block entities, entities, origin, and air semantics into Connector-owned data;
then use the existing preview/details/upload flow.

Do not simulate Ctrl+C or call `Selection.callCopy` as a convenience. Inspection
of its shared copy-action path found placement cancellation and selection
clearing. `SelectionBuffer.callCopy` returns an asynchronous snapshot result and
is a possible lower-level selection integration, but needs a separate review of
its flags, server data requests, permissions, entity capture, and ownership.
Clipboard upload is the narrower first implementation.

### Preserve data, not just block counts

In the inspected 26.2 jar, `loadLitematic` and `loadSponge` construct their
clipboard objects with an empty entity list. The Sponge path also retains the
original NBT as additional schematic data; that does not establish that entities
will participate in placement. A clipboard can carry entity NBT, and native
blueprints include it.

Reusing the loader alone therefore does not prove lossless transfer. Verify
armor stands/item frames separately from block entities such as chests and signs.
Handle any unsupported data explicitly. Also check sparse selections, negative
coordinates, signed Litematica region sizes, air versus unselected positions,
orientation, and the placement anchor. A matching bounding box is insufficient.

### Use files for a later Blueprint Browser connection

Axiom documents sharing `.bp` files through its configurable blueprint directory,
including subfolders. Its browser watches filesystem changes. A later
**Save to Axiom library** action could use a dedicated Schematio subfolder and
atomic, uniquely named writes after blueprint conversion is verified.

Do not replace `BlueprintBrowserWindow`'s global callback or server registry to
turn it into a Schematio browser. Those belong to Axiom's active workflow. Native
blueprint support is additional scope; the first transfer can use schematics.

## Implementation order and validation

First resolve input, callback, and viewport ownership in `panel-lib`. Then add a
small optional `AxiomBridge` beside `LitematicaBridge` and `WorldEditBridge` in
Connector, with explicit capabilities and a disabled fallback for unknown
contracts. Wire clipboard import and clipboard upload through the existing
detail panel and export-source picker. Keep Axiom types out of the general UI.

Before shipping, use isolated game instances to cover:

- Axiom absent; Axiom present but inactive; editor active; placement in progress.
- Axiom with Connector, then with Litematica and WorldEdit installed together.
- All six supported Minecraft targets, with packaged startup and in-game results
  recorded separately.
- Local world, server without Axiom support, and server with import denied.
- Opening/closing both UIs, text entry, mouse look, scroll, Escape, key release,
  window focus changes, and resource reloads.
- Failed/cancelled downloads, changing worlds mid-download, replacing the
  clipboard while a download runs, and upload previews that remain stable while
  the player edits their source.
- Block states, block entities, entities, origins, air, transforms, and Axiom undo
  after the player confirms a placement.

No game profile, installed mod, or world was changed during this investigation.
No Connector build, unit test, packaged startup, or in-game compatibility test
was run for an Axiom integration. The work completed here is download verification
and static investigation.

## Provenance

Official release metadata:
[version feed](https://api.modrinth.com/v2/project/axiom/version),
[1.21.10 release](https://api.modrinth.com/v2/version/LNCxTWoz),
[26.2 release](https://api.modrinth.com/v2/version/o59cWLPI).
Both downloaded jars matched their publisher's file size and SHA-512 checksum.

| Jar | SHA-256 |
| --- | --- |
| `Axiom-6.0.5-for-MC1.21.10.jar` | `deb1c74851d73861713868c61b2fff108dac01408e2bd80867a64f87a682c88e` |
| `Axiom-6.0.5-for-MC26.2.jar` | `15c105971eeaab4c91cd040f8d2bb305d8560972a4b98003b6d91610ba5ba2db` |

Public API source inspected at
[`5abf940`](https://github.com/Moulberry/AxiomClientAPI/tree/5abf940df27e67419b15d0e9db788ac75ae8069a);
official example at
[`ad252d0`](https://github.com/Moulberry/AxiomClientAPIExample/tree/ad252d0a8b19de96e7732ffd95386bc60e65d3cc).
Local jars, release metadata, jar inventories, and targeted `javap` output are
under the ignored `build/axiom-research/` directory. This document records findings
and integration requirements; it does not include Axiom implementation source.
