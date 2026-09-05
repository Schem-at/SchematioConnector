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

## Kineglyph and article delivery

The four diagrams use original isometric block illustrations, a library view, a
community key, and a server view. They were authored with the local Kineglyph
project, including its new `minecraftCommand()` recipe and terminal wrapping fix.
The library changes are in [Kineglyph PR #3](https://github.com/Nano112/kineglyph/pull/3)
(`4f521ba`). Shared article files are implemented in
[Schematio PR #91](https://github.com/Schem-at/schemati/pull/91).
Regenerate the illustrations with Node 24 or newer and built Kineglyph packages:

```sh
node scripts/render-article-figures.mjs /path/to/kineglyph
```

All 16 final SVG layouts, at 320, 390, 720 and 960 pixels, resolved without layout
diagnostics. The command example has MP4 exports at 320 and 720 pixels, native
play/pause controls, and SVG stills for reduced motion. Kineglyph also pads odd
video dimensions for H.264 export. The illustrations use no Minecraft textures
or bundled game font. The article uses responsive pictures. Its SVGs work under
Schematio's existing policy that disables author-supplied JavaScript. Scene source
is kept in Git and excluded from the published bundle. No host policy was changed.

Pagina (`f48a404`) built the article and verified its PGZ bundle. The bundle was
imported into local Schematio with `status: draft`. The seven 1.3.3 jars are
registered in Schematio’s shared Downloads system and attached to this article.
The draft labels them as release candidates. Their `/dl/.../download` links were
fetched over HTTP and all seven response bodies matched the CI SHA-256 hashes.
The file names, public slugs, requirements and hashes are recorded in
[hosted-downloads.json](hosted-downloads.json). GitHub remains linked for source,
release history and issues. The example ZIP avoids the host's current
file-route limit of eight extension characters, which rejects `.litematic` paths.

The article was checked against the project's no-ai-slop skill and evaluation:
plain instructions, specific commands and button labels, claims limited to code
and observed behavior, no promotional filler, and a concrete issue-reporting end.

## Local article and download verification

The draft and all 27 supporting assets return HTTP 200. The shared download list
contains seven jars, and reimporting the article preserves those associations.
The article, download list, and sharing controls were reviewed in Chrome against
the local PostgreSQL application. At a 390-pixel viewport, the page has no
horizontal overflow and selects the narrow diagram source. All six game captures,
four illustrations, and command media loaded. Source scenes remain outside the
bundle and the page contains no article-authored scene modules.

Download feature tests: 41 passed, 125 assertions, one existing skipped presigner
test. Checks include reuse across two articles, detaching and deleting articles,
password gates, inactive/expired/missing files, escaped metadata, both admin forms,
checksum verification, and idempotent release ingestion. Browser checks also found
and fixed PostgreSQL UUID/varchar media comparisons and DISTINCT over article JSON.

The Kineglyph branch also passes a clean workspace bootstrap and all 716 tests.
Its Docs CI still fails Node prerendering of seven pre-existing browser-only
Nucleation/Minecraft examples (`location` / `customElements`). This does not affect
the exported Connector figures or videos; Kineglyph PR #3 remains a draft.

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
