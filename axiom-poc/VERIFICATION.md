# Minecraft 26.2 / Axiom 6.0.5 test record

Tested locally on 6 September 2026. The prototype lives on `feat/axiom-26.2-poc`, based on Connector commit `e4d9b60`, in its own worktree. Existing Connector, MCInspector and installed Minecraft profiles were left untouched.

## Build and unit tests

`./gradlew -p axiom-poc build --console=plain` passed with Java 25, Loom 1.16.3, Minecraft 26.2, Fabric Loader 0.19.3 and Fabric API 0.155.2+26.2. Six JUnit tests passed. They cover response limits without Content-Length, HTTPS/loopback endpoint rules, the real search query contract, cancellation, superseding requests, clipboard replacement, world changes, revoked permissions and active placement.

The jar is **19,744 bytes**. Archive inspection found only the add-on's classes and metadata, with no nested jars, Axiom classes, ImGui classes, mixins or native binaries.

```text
Schematio-Axiom-mc26.2-0.1.0-poc.1.jar
SHA-256 ac550c87749459c80f2d49cfc95765eecc12950d933d7d9c16a8adfbbae791d8

Unmodified Axiom-6.0.5-for-MC26.2.jar
SHA-256 15c105971eeaab4c91cd040f8d2bb305d8560972a4b98003b6d91610ba5ba2db
```

## Packaged startup

Both checks used Loom's production client task and normal Fabric mod discovery, rather than a development classpath alone.

- With Axiom: the packaged client entered the isolated Copperlight Workshop world and registered the Schematio custom tool. MCInspector observed the active editor and tool.
- Without Axiom: Minecraft reached its accessibility onboarding screen. Fabric reported `schematio_axiom=true` and `axiom=false`; the integration logged that it was inactive. No missing-Axiom class error occurred. [Recorded game state](evidence/without-axiom-state.json).

MCInspector 0.1.0, Fabric Language Kotlin and panel-lib 0.1.1 were present as test instrumentation. They are not dependencies of the shipped prototype. Offline test accounts produced the expected profile-key HTTP 401 log; no multiplayer authentication was attempted.

## In-game checks

Typing `tree` and clicking Search returned the five matching public builds from `https://schemat.io/api/v1`. The final jar fixes an input binding issue found during testing: clicking Search had previously submitted stale text unless Enter was pressed first.

The public [oak tree by LukeCraft2010](https://schemat.io/schematics/X2rTZt) downloaded as a 2,207-byte Sponge schematic and became a 21,168-cell Axiom clipboard, including air. Axiom generated its native thumbnail and placement preview. One recorded import took 1,079 ms to download, 25 ms to decode on the worker, and 9 ms from decode completion through the queued client handoff. That last number includes waiting for the client thread; it is not a measurement of time spent occupying that thread.

The test invoked Axiom's native Paste action through MCInspector and confirmed placement with Enter through Axiom's GLFW callback. It invoked Axiom's native Undo action and compared all block states in the affected volume. The SHA-256 hash after undo exactly matched the hash before paste. [Block-state evidence](evidence/paste-undo.json). Paste Air was disabled through Axiom's placement state; random ticks were disabled in this disposable world. This verifies blocks, not entity or inventory restoration. Synthetic Cmd/Ctrl+V input did not reliably trigger the action, so the keyboard Paste shortcut is not counted as verified.

The packaged adapter and controller also passed [live fixture checks](evidence/runtime-checks.json): a valid one-block schematic, invalid compression, wrong schematic version, excessive volume, entity rejection, cancellation during a delayed download, and a clipboard change during a delayed download. The latter two preserved the player's clipboard. The helper temporarily used a loopback fixture endpoint and restored the public endpoint and original clipboard afterwards.

## Frame sampling

The final sample is in [frame-samples.json](evidence/frame-samples.json). The benchmark compares Axiom's Box Select tool with the Schematio tool showing five search results and the same idle clipboard preview. It checks that the expected tool is active for every sample. Each condition has 160 samples, collected in A/B/B/A order with two seconds to settle before each block.

| Active tool | Median frame processing | 95th percentile | Median FPS |
| --- | ---: | ---: | ---: |
| Axiom Box Select | 0.94 ms | 1.63 ms | 120 |
| Schematio, five results | 0.87 ms | 1.49 ms | 120 |

The difference is too small and the test too short to claim a speed improvement. Sampled FPS ranged from 112–120 for Box Select and 109–120 for Schematio. This checks the cost of the populated panel relative to another Axiom tool with the add-on installed; it does not measure total overhead relative to a process without the add-on.

Hardware was an Apple M2 Max MacBook Pro with 64 GB RAM. The JVM used a 3 GB heap and four active processors. The window was 1440 × 900 logical pixels on a Retina display, render distance eight chunks, VSync off and an FPS limit of 120. Inactivity throttling was set to minimized-only. MCInspector's overlay stayed closed. The camera, clipboard and world were held constant; the world clock was stopped at midday.

The timer is Minecraft's `getFrameTimeNs()`, sampled through MCInspector. It measures frame processing rather than GPU time or input latency. The two tools have similar local frame costs in this small scene. These measurements do not establish performance at the volume limit, on lower-end hardware or on a remote server.

## Before incorporation into Connector

Test the same workflow on a remote server with Axiom permissions enabled and denied, then exercise large builds, block entities and other supported versions. Add entity handling before accepting schematics containing entities. Private libraries and uploads need account authorization and their own tests. The internal clipboard adapter must be reviewed against each new Axiom version; the current version gate intentionally leaves untested versions inactive.
