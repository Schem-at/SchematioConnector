# Illustrated article capture notes

Captured on 5 September 2026, after PR #1 merged as
`e4d9b601416cb9a3c4e52f49fa57c7f9cdc8caff`.

## Screenshots and functional checks

All six article PNGs are direct MC-Inspector captures from Minecraft 26.2 with
Connector 1.3.3. Copperlight Observatory is an original demonstration build;
the article includes a ZIP with its WorldEdit and Litematica files.

| Image | Observed behavior |
| --- | --- |
| `copperlight-observatory.png` | WorldEdit loaded and pasted the observatory on Paper 26.2. |
| `bridge-server-load.png` | Clicking **Load on server** fetched the private schematic through the local Schematio backend and filled the player's Paper WorldEdit clipboard. The panel displayed success. |
| `litematica-placement.png` | Clicking **Load into Litematica** downloaded the conversion and created a separate placement beside the pasted build. |
| `preview-composer.png` | The live composer rendered the model; zoom and Capture preview worked. |
| `bridge-upload-draft.png` | The plugin uploaded its clipboard to the local backend. The mod fetched the player-owned draft and opened the details form. Name and description were filled; this capture does not show publication. |
| `local-worldedit.png` | **To WorldEdit clipboard** loaded the integrated server's clipboard in singleplayer with WorldEdit Fabric 7.4.5. Export returned 12,216 blocks, dimensions 41 × 39 × 41, and the same block-type counts as the source. |

The local WorldEdit session needed a first WorldEdit command (`//pos1`). The
article includes that step. The bridge screenshot used Paper WorldEdit 7.4.5;
the local clipboard screenshot used the Fabric WorldEdit mod.

The local demonstration account and short-lived client session were provisioned
for these captures. HTTP requests, signatures, file conversions, clipboard loads,
and server draft creation used the real local Schematio application. This does
not validate Mojang sign-in or a production account session. Earlier six-version
runtime checks are recorded separately in [release-readiness.md](release-readiness.md).

## Kineglyph, Nucleation, and article delivery

The plugin illustration uses a Nucleation render of the actual Copperlight `.schem`,
composited with Kineglyph's labels and grid on a transparent background. The bridge
has a compact detection sequence. The local-tool and community poster diagrams were
removed; the article uses the real game captures and setup instructions there.

The Minecraft chat demonstration is an animated SVG. Kineglyph's new
`exportAnimatedSvg()` samples its timeline into vector frames with CSS keyframes.
It plays once, holds `//paste`, and shows the completed command immediately for
reduced motion. No video or article-authored JavaScript is required. The article
now directs readers to Schematio's **Copy in-game command** action and keeps
`//paste` in its own copyable block after the clipboard confirmation.

The library changes are in [Kineglyph PR #3](https://github.com/Nano112/kineglyph/pull/3)
(`212e7c5`). Shared article files and Pagina's host integration are in
[Schematio PR #91](https://github.com/Schem-at/schemati/pull/91).
Regenerate with a Nucleation Python environment and built Kineglyph packages:

```sh
python scripts/render-article-build.py copperlight-observatory.schem resource-pack.zip
node scripts/render-article-figures.mjs /path/to/kineglyph
```

All 12 SVG layouts at 320, 390, 720 and 960 pixels resolve without diagnostics.
The article selects 320, 390 or 720 to keep text readable in its reading column.
Source scenes and the Nucleation source render remain in Git; the bundle includes
only the referenced final media. The native render uses an isometric camera, yaw
35°, pitch 28°, zoom 1.38, a transparent background and ambient light 0.5.

Schematio explicitly permits reviewed article authors to run live scenes through
`CSP_AUTHOR_SCENES=true`; this was a host trust setting, not a Pagina limitation.
The Connector AGENTS instruction now reflects the owner's authorization. Pagina's
standalone SVG responses allow inline styles and data images while retaining the
script-disabled sandbox. That permits both SVG animation and embedded build renders.

Pagina (`f48a404`) packed and verified the article, which was imported locally with
`status: draft`. Seven 1.3.3 jars remain attached through shared Downloads. All seven
response bodies were previously verified against the CI SHA-256 hashes; catalog
label updates do not replace their bytes. [hosted-downloads.json](hosted-downloads.json)
records filenames, public slugs, requirements, group and variant labels, and hashes.
GitHub stays linked for source, releases and issues. The example ZIP contains both
`.schem` and `.litematic` files.

The article passes the project's no-ai-slop editing checks: concrete actions and
button labels, commands copied one at a time, claims bounded by code and observed
behavior, and no promotional filler.

## Local article and download verification

The draft and all 16 referenced supporting assets return HTTP 200. The download
catalog contains seven jars, grouped into Fabric and Paper with versions sorted
naturally. Expanding a row reveals requirements, file details and SHA-256. Admin
Downloads searches version, group, title and filename and filters by group.

Chrome review covered desktop and 390-pixel layouts, expanded download rows,
version search, the real build composition, and the standalone animated SVG's
initial and completed frames. The copy button reports **Copied** for `//paste`.
The phone page has no horizontal overflow. All six game captures and three SVG
illustrations load. There are no video elements or article-authored scene modules
on this page, although trusted scene support is enabled for articles that need it.

Download and Pagina feature checks cover shared files, access gates, checksums,
import idempotency, catalog ordering, admin search/filtering, and both SVG and
scene policies. The Kineglyph export typecheck and all 722 tests pass.

Kineglyph's Docs CI still has a separate Node-prerender failure in seven existing
browser-only Nucleation/Minecraft examples (`location` / `customElements`). It does
not affect these exported SVG assets; PR #3 remains a draft.

## Open publication checks

- The current local backend protects private preview images through its web
  session policy, while Connector fetches preview URLs without authentication.
  A fresh private-preview request returned 404 even though metadata and the
  schematic download succeeded. Making this demonstration build public on the
  local instance allowed its preview to load. Resolve private-preview access
  before describing private-library previews as publication-ready.
- Mojang sign-in, authenticated local-file upload, the production signing-key
  configuration, and the production bridge check remain open in the main
  readiness document. Neither 1.3.3 nor this article has been published.

## MC-Inspector capture fix

After a composer capture, OpenGL retained a pack row length of 1280. MC-Inspector's
screenshot reader assumed tightly packed rows, producing striped captures.
`FrameCapture.kt` in the local MC-Inspector checkout now saves, resets and restores
pack row length and skip offsets. A rebuilt 26.2 jar captured a clean frame with
row length 1280, skip rows 2 and skip pixels 4, and restored all three values.

Compilation and jar assembly passed. The MC-Inspector suite passed 47 of 48 tests;
its tool-documentation parity check fails on the existing tool registry/docs
mismatch. The capture fix and the earlier 26.1/26.2 inspector compatibility edits
remain local to that project. They are not Connector release changes.
