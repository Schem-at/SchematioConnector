# Fabric Client UI - Manual Testing Checklist

Run the dev client against the **local schemati backend**.

The Fabric module is multi-version (a `:fabric:<version>` subproject per MC version),
so launch ONE version, not all of them:

- **IntelliJ:** run a `Client <version>` config from `.run/` (e.g. `Client 1.21.11`).
- **A specific version on the CLI:** `./gradlew :fabric:1.21.8:runClient`.
- **The active version on the CLI:** `./gradlew :fabric:runClient` delegates to ONLY
  the currently active version (set in `fabric/stonecutter.gradle.kts`), so it starts a
  single client. (Switch the active version with `./gradlew "Set active project to <version>"`.)

```bash
export JAVA_HOME=/Users/harrison/Library/Java/JavaVirtualMachines/ms-21.0.7/Contents/Home
# active version (currently 1.21.11):
./gradlew :fabric:runClient
# ...or a specific version:
./gradlew :fabric:1.21.11:runClient
```

**Config:** the dev client reads `fabric/run/config/schematioconnector/config.properties`
(a production install uses `~/.minecraft/config/schematioconnector/config.properties`).
Point it at the local app before launching (create the file if missing):

```properties
api_endpoint=https://schemati.test/api/v1
trust_all_certificates=true
```

Notes:
- `trust_all_certificates=true` is required for the local `.test` TLS cert and also
  whitelists `localhost`/`*.test` hosts for preview images.
- A cached `client_token` line appears after first auth; delete it to force a fresh handshake.
- Litematica/WorldEdit **sources and loading** arrive with Plan 4 - in this build both
  bridges are no-ops, so expect "limited mode" (see step 12) and no
  "Load into Litematica"/"To WorldEdit clipboard" buttons.

## 1. Auth on launch
1. Start the client, log into any world (singleplayer is fine).
2. Open Home (press **K**, or run `/schematio`). Go to **Settings**.
3. Verify: endpoint shows `https://schemati.test/api/v1`; status shows
   "Signed in as <your MC name> (<uuid>)" within a few seconds of launch.
   (Dev clients use an offline Mojang session - if auth fails with "No valid Minecraft
   session", verify the backend's mojang auth accepts the dev session or test with a
   real launcher profile.)
4. Press **Re-authenticate** - spinner, then "Authenticated successfully" banner.
   This now FORCES a fresh token: the cached `client_token` (in-memory + in
   `config.properties`) is discarded and a new Mojang handshake runs, so newly
   granted backend permissions (e.g. `upload_schematic`) are picked up
   immediately even when the old token had not expired yet.
   - Upload self-heal: if an upload hits a 403 "Insufficient permissions"
     (stale cached token from before the backend permission change), the wizard
     automatically forces a re-auth and retries the upload ONCE. Only if it
     still fails does it show "Upload was rejected. Your account may not have
     upload permission, or the server needs updating."
5. Press **Clear cache** - banner confirms; listings refetch on next tab visit.
6. Press **Open config folder** - Finder opens the run-dir config folder.

## 2. Tab walk-through
7. **Browse**: centered grid of public schematics with aspect-correct (cover-cropped)
   preview thumbnails and a two-line name/author strip; context dropdown
   (Public / Mine / your communities); search debounces (~300 ms) and is capped in
   width; sort + order cycle buttons re-query (they wrap onto a second controls row on
   narrow screens, e.g. GUI scale 3); infinite scroll loads further pages.
   A **Tag: All** cycle button is shown for EVERY context (Public/Mine included),
   populated from the global minecraft tag tree (children indented); picking a
   community context appends that community's own tree to the options. Cycling to a
   tag re-queries the listing filtered to that tag, **All** clears the filter, and
   switching context resets it back to All.
8. **My Schematics**: pinned to your uploads; **Upload** button on the left.
9. **Communities**: pending invitations on top (Accept/Decline), your communities below
   (member count + role chips). **Quick Shares**: your shares listed with expiry/uses.
10. **Settings**: as in step 3. Tab selection persists when closing/reopening Home.

## 3. Browse → detail → save to disk
11. Click a schematic in Browse → detail screen: preview (letterboxed/contained,
    aspect-correct), name, author, tags, stats. Descriptions render styled -
    bold/italic/underline/strikethrough and "• " bullets (wrapped bullet lines
    hang-indent under their text); no raw HTML tags (`<p>`, `<strong>`, ...)
    should ever be visible.
12. **Save to disk** → success banner with the path; verify the file exists
    (`fabric/run/schematics/` or the path shown).
13. **Back** returns to Home with prior tab/filters intact.

## 4. Upload wizard (end-to-end, local file source)
14. My Schematics → **Upload**. When Litematica has a current selection (a selected
    placement, else the current area selection) the wizard PRESELECTS it and starts
    directly on **Step 2 - Details**, showing "Source: <label>" with a **Change...**
    button that reopens the source picker. With no current selection (and no
    preselect) it starts on the source step as before. Source step: pick a
    Litematica placement/area selection, the WorldEdit clipboard, or a
    `.litematic`/`.schem` from disk.
14a. Every step always shows **Cancel** (left), **< Back** (when applicable) and
    **Next > / Upload** (right) at the bottom - even while tags/communities are
    still loading (loading only shows "Loading tags..." inside the inline tag
    picker's tree, nothing else). The details form renders fully: Name,
    Description, Visibility, Community, Preview slot + **Compose preview...**
    on the left; the INLINE tabbed tag picker (no popup, no "Edit tags..."
    button - see 15) above the co-author editor (lookup field, Add button,
    rows) on the right.
14b. Description editor: a SINGLE inline WYSIWYG surface - you type directly
    into the rendered view (no raw markup, no separate preview pane). The
    toolbar above it has **B / I / U / S** plus five color "A" swatches
    (default, brand fuchsia, red, green, blue). Type and the formatting shows
    inline immediately; a blinking accent caret marks the position. Click to
    place the caret, drag (or shift+arrows/click) to select - the selection is
    highlighted; double-click selects a word. With a selection, B/I/U/S/color
    style it (toggle: pressing again on a fully-styled selection clears it,
    and the button lights up while the selection has the style); with no
    selection they set the style of the next typed characters (focus returns
    to the surface after a toolbar press, so just keep typing).
    Ctrl+A/C/X/V select-all/copy/cut/paste (plain text); Enter inserts a line
    break (blank line = paragraph); Home/End/Up/Down navigate wrapped lines;
    overflow scrolls (mouse wheel) and follows the caret. Empty shows a faint
    "Description..." placeholder. Loaded links/colors/bullets render styled
    and survive editing + save, though links/bullets have no creation UI.
15. Fill name, description, tags, visibility (mirror the website fields); submit.
    Tag selection is INLINE in the details step (no popup) and TABBED: the
    **Minecraft** tab (global tag tree) is the default; the **Community** tab
    holds the selected upload community's tree and is labeled with its name.
    With Community = None that tab shows "Pick a community to use its tags";
    cycling the community swaps the tab's tree (and drops the old community's
    checks - Minecraft-tab selections survive). Search scopes to the active
    tab; the "Selected" chips show selections from BOTH tabs. Greyed-out rows
    are auto-assigned tags (not manually assignable) - visible for hierarchy
    but not toggleable. Filter values (enum/bool cyclers, numeric fields) for
    selected tags appear under the chips and are validated before **Next >**.
    The description is submitted as HTML (`spansToHtml`); verify it renders as
    paragraphs on the website.
16. Verify success: the wizard closes back to the game and a chat message appears -
    gray `[Schemat.io]` prefix, "Uploaded "<name>" successfully", a clickable
    **[Open in browser]** (opens the schematic's web page; hover shows the full url)
    and **[Copy link]** (copies the url to the clipboard). The schematic appears in
    My Schematics, and on the website (`https://schemati.test`) under your account.
17. Validation check: submit with an empty name → field error shown (422 mapping).

## 5. Edit schematic
18. Open one of your schematics → **Edit**. Rename it, change tags, add a co-author
    (player-name lookup), save. The tag picker popup is tabbed: the **Minecraft**
    global tree is the default tab, then one tab per joined community (and a
    "Current tags" tab for attached tags outside the loaded trees);
    non-assignable tags render disabled (a currently-attached one stays
    checked-disabled and is preserved on save).
19. Verify changes on the detail screen after save and on the website.
    Description behavior: the editor (same inline WYSIWYG surface as the
    wizard, item 14b) loads the stored HTML and shows it fully styled -
    including `<span style="color: ...">` colors, links and bullets. Leaving
    it untouched must NOT modify the description server-side (change detection
    compares normalized HTML, so it is omitted from the update); editing it
    saves the edited document back as sanitizer-safe HTML with all untouched
    formatting preserved.

## 6. Communities
20. (Setup: from the website, invite your MC account to a test community where you can
    moderate.) Communities tab → invitation row → **Accept** → joined banner; the
    community appears in the list.
21. Open the community → detail screen: members list (roles as chips), Browse
    schematics button scoped to the community.
22. Member moderation (needs permissions): **Kick** a test member (confirm dialog);
    edit a member's **roles** (role toggle dialog) → "Updated roles" banner.
23. Tag management (needs manage-tags): **Add tag**, edit an existing tag, change its
    scope; verify on the website.
24. **Leave** (on a community you don't own) → confirm dialog → removed from list.

## 7. Quick shares
25. Quick Shares → **New share**: pick one of your schematics, set name/expiry/max
    uses → create. Share appears in the list with expiry countdown.
26. **[Copy]** → paste the link in a browser → the share page loads (logged out works).
27. **[Revoke]** → confirm → "Quick share revoked"; the link stops working.
28. Detail screen → **Quick share** button creates a share for that schematic directly.

## 8. Offline behavior
29. Stop the local backend (e.g. stop Herd/`docker compose down` for schemati).
30. In Settings press **Re-authenticate** (or wait for the next `/mod/me` refresh) so
    the client notices: Settings shows the orange "Offline" line.
31. Browse/Communities: cached listings still render with the yellow
    "Offline - showing cached results" stale banner; Retry refetches once the backend
    is back. Quick Shares (uncached) shows the offline error banner with Retry.
32. Mutating buttons are disabled with an "Unavailable while offline" tooltip:
    Upload (My Schematics), Accept/Decline invitations, New share, Leave/Add tag in
    community detail, Quick share/Edit/Delete on schematic detail. Row-level
    [Revoke] shows the offline notice instead of the confirm dialog.
33. Restart the backend, press Refresh/Re-authenticate → banners clear, buttons
    re-enable (switch tabs to rebuild widgets).

## 9. Limited-mode notice (no Litematica/WorldEdit)
34. Run a client without Litematica or WorldEdit in the mods folder (the bridges fall
    back to no-ops), delete `limited_mode_notice_shown=true` from config.properties if
    present, then join a world: one chat message explains that browse/upload work but
    loading/exporting needs Litematica or WorldEdit; the log shows a matching warning
    each join.
35. Rejoin: no second chat message (`limited_mode_notice_shown=true` persisted).

## 10. Litematica & WorldEdit bridges (Plan 4)

With Litematica (+malilib) in the dev runtime:

36. Browse → schematic detail → **Load into Litematica**: the `.litematic` lands in
    `<litematica schematics dir>/schemat.io/`, a placement is created at the player and
    selected; an INFO banner confirms. Log shows the bridge install line
    ("Litematica detected - load/export bridge installed.") at startup.
37. Litematica's **Load Schematics** GUI shows a "Schemat.io" button (mixin); clicking
    it opens our Home screen on the Browse tab.
37a. Litematica's **Load Schematics** GUI also shows an "Upload to schemat.io" button
    (left of "Schemat.io"). Select a `.litematic`/`.schem`/`.schematic` file → click it
    → the Upload wizard opens on the Details step with that file pre-selected as the
    source ("Change..." reopens the picker; the file is prepended to the list if it's
    outside the picker's normal scan, e.g. deep subfolders). With no
    selection or a directory selected → ERROR message "Select a schematic file to
    upload first". Select a `.nbt` file → ERROR "Cannot upload <name>: only .litematic,
    .schem and .schematic files are supported"; the wizard does not open.
38. Litematica's **Schematic Placements** GUI shows an "Upload to schemat.io" button
    when a placement is selected; clicking it opens the Upload wizard on the Details
    step pre-seeded with that placement as the source.
39. Upload wizard / Quick share source pickers list loaded placements and area
    selections. Export a placement (file-backed and freshly created in-memory) and an
    area selection (must be in a world; out-of-world gives a clear error) - both upload
    valid `.litematic` files.

With WorldEdit in the dev runtime (singleplayer only - by design the client bridge
needs the integrated server; on multiplayer servers `isAvailable` stays false and the
server-side plugin covers clipboard flows):

40. In singleplayer, `//pos1`+`//pos2`+`//copy` a build, then Upload wizard → source
    "WorldEdit clipboard" → uploads a `.schem` (Sponge v3). Same via Quick share.
41. Schematic detail → **To WorldEdit clipboard** (visible only in singleplayer): then
    `//paste` places the schematic. The action downloads the `schem` conversion;
    non-Sponge data is rejected with "WorldEdit cannot read .<fmt> files…".
42. Empty clipboard → friendly "//copy something first" error; no session yet → "run a
    WorldEdit command once" hint.
43. On a multiplayer server with WorldEdit installed client-side only: the WE actions
    are hidden/unavailable (integrated-server-only scope), and if Litematica is also
    absent the limited-mode notice (§9) still fires - expected.

Absence behavior:

44. Remove Litematica (and/or WorldEdit) from the runtime: startup logs the
    corresponding "not available … disabled" warnings, no mixin errors (conditional
    mixin skips silently), UI hides/disables the bridge-gated actions, and §9 applies
    when both are missing.

## 11. Tag selector (browse filters + tag assignment)

Tree basics (any entry point):

45. Browse tab → **Tags...** opens the selector as a centered panel over the dimmed
    screen. It is TABBED (the same panel as the inline upload picker): a
    "Minecraft" tab (global tree) selected by default, plus the community's tab
    while a community context is selected (a single tree shows no tab bar).
    Per tab: roots expanded one level, collapse/expand triangles on nodes with
    children. Cancel / ESC returns without changing the listing.
46. Search: typing in the top field live-filters the ACTIVE tab to substring matches
    shown flat with their ancestor path (e.g. "Blocks > Stone > Granite"); clearing
    the field restores the tree with the previous expand state; no matches shows
    "No tags match".
47. Selecting rows adds chips to the "Selected" strip (colored by tag color) - the
    strip shows selections from ALL tabs, whichever tab is active; clicking a
    chip's "x" removes it. Overflowing chips collapse into a "+N" suffix.

Browse filtering (FILTER mode):

48. Select 2+ tags → Done: the summary next to the Tags button reads "2 tags" (a single
    tag shows its name) and the grid reloads with AND logic - only schematics carrying
    every selected tag appear (`tags=` comma list in the request).
49. Select a tag with a numeric filter, set Min and/or Max on its filter row → Done:
    summary gains ", 1 filter" and the listing narrows (`filter_min[..]`/`filter_max[..]`).
    Non-numeric min/max text shows an inline error and blocks Done; enum/bool filters
    offer an Any/value cycler instead.
50. Reopen the selector: previous tags + constraint values are restored, and branches
    containing the selected tags start expanded (the checked rows are visible without
    manual expanding). Remove all chips → Done: summary returns to "All tags" and the
    listing is unfiltered. Switching context resets the tag selection.
50b. Close the WHOLE GUI (ESC out of Home) and reopen it: the Browse/Mine tag
    selection, filter constraints, search text, sort and context survive the reopen
    (per tab flavor); **Tags...** opens seeded with the prior selection.

Assignment (ASSIGN mode - upload details step and edit screen):

51. Upload wizard details step / edit screen show a chip row + **Edit tags...** (the
    button stays disabled until the trees load). Non-manually-assignable category nodes
    render grayed and cannot be toggled; in FILTER mode (browse) they can.
52. Select a tag carrying filters: per-filter input rows appear under the chips -
    enum → value cycler with Unset, bool → True/False/Unset, int/float → text field
    with the unit in the label and a live validation error for out-of-range/non-numeric
    values. Defaults seed when the tag is first selected.
53. A required filter is starred (*); Done with it unset shows "Required filter(s) not
    set: …" and keeps the dialog open. Setting the value lets Done close; the chips row
    updates on return.
54. Edit screen: existing tags preselect (including id-bearing tags outside the loaded
    trees under a "Current tags" section - deselect/reselect works and their ids are
    re-submitted while selected; id-less tags show as "(kept as-is)" chips only).
    Existing filter values from the schematic seed the inputs. Save sends `setTags`
    only when the selection or filter values changed; saving persists across reopen.
55. Upload: chosen tags + filter values reach the new schematic (check the website
    detail page); the confirm step's "Tags: N" count matches the chips.
55b. Selection persists across selector reopens for EVERY caller: pick tags → Done →
    **Edit tags...** / **Tags...** again - the picker reopens with the same tags
    checked, the same filter values/constraints filled in, and the selected branches
    expanded. Verify on (a) Browse tab filter, (b) edit screen, (c) upload wizard
    (including after a Details ⇄ Source step round-trip).

Design system (restyle):

55c. The detail, edit and tag-selector screens use the shared design system: opaque
    scrim/panel, bold accent-underlined header, uppercase faint section labels,
    SURFACE_ALT input wells with 1px borders (accent while focused), FlatButton
    variants (detail: export actions secondary, Edit primary, Delete danger, Back
    ghost; edit: Save success, Back ghost; selector: Done primary, Cancel ghost),
    shadow-free text everywhere. The detail preview sits in a 16:9 card (contain),
    metadata as label/value rows, tags as tinted chips, authors as head-avatar rows.
    The co-author editor lists head-avatar rows with a ghost "x" remove and a themed
    add-field well; Mojang lookup + min/max limits unchanged.

## 12. Opaque panel backgrounds (no world bleed-through)

56. In a world, open each of these and verify the content sits on an opaque dark
    backdrop - the world must NOT show through behind labels/fields (only the
    vanilla blur/dim may peek at most around dialog panel edges):
    - Home (all tabs), schematic detail, schematic edit, community detail,
      Upload wizard (every step), Quick share create (every step) → full-screen
      opaque scrim.
    - Confirm dialogs (delete/revoke/kick/leave), the community tag edit dialog
      and the member roles dialog → centered opaque panel over the dimmed world.
    - Tag selector keeps its existing opaque panel (unchanged).

## 13. One-click quick link (Mine tab)

57. My Schematics tab: a **Quick link** button sits right of **Upload**. Click it →
    the Quick share create screen opens on the source step listing the same
    Litematica/WorldEdit/local-file sources as the upload wizard.
58. Pick a source → details step defaults to 24 hours expiry, no password, no max
    uses → **Create** → the screen closes back to the game and a chat message
    appears: "Quick share created" with a clickable **[Open link]** (hover shows
    the url) and **[Copy link]**; paste/open the link in a browser and the share
    page loads (mirrors the plugin's quickshare).
59. While offline the Quick link button is disabled with the offline tooltip (same
    gating as Upload).

## 14. Thumbnail capture (offscreen renderer)

> **Acceptance checklist (one pass, in order).** Details for each line live in
> the referenced items/subsections below.
>
> - [ ] Opaque panels everywhere, no world bleed-through (§12).
> - [ ] One-click Quick link flow works from the Mine tab (§13).
> - [ ] **SPIKE gate:** Debug capture test PNG is non-blank (item 60) - if it
>       fails, flip the fallback flag (item 64) before testing anything below.
> - [ ] Render source loads for placement / area selection / local file -
>       each is captured ONCE into a frozen snapshot (Render source 1-5).
> - [ ] 3-pass fidelity: blocks lit + correct layers, fluids in place,
>       chests/signs/beds render, biome tint plausible (Offscreen renderer 1-5).
> - [ ] Composer AUTO-FITS on open: the build fills the 16:9 preview with a
>       ~10% margin immediately, in both projections (usability round below).
> - [ ] Isometric vs perspective both frame the build; presets (Front/Iso/Top)
>       reset the pose AND re-fit; switching projection re-fits too
>       (Offscreen renderer 6, items 66/69).
> - [ ] Large build (>96 blocks on an axis) shows the down-sample note and
>       still previews - only a CENTERED 96-clamped sub-region is rendered and
>       framed, so the preview shows the build's middle, not the whole thing,
>       and the client must NOT freeze (Render source 6, item 66).
> - [ ] Composer orbit (left-drag) + pan (RIGHT-drag) + zoom (scroll) feel
>       right at any GUI scale - same control scheme as the website's
>       OrbitControls. Panning slides the build in the SCREEN plane (left/
>       right/up/down with the cursor, ~1:1) at any yaw/pitch and in both
>       projections; drags on widgets don't orbit or pan (item 68). **Fit**
>       re-frames at the current angle AND recenters (clears pan); **Reset**
>       returns to the fitted, centered Iso pose; presets and projection
>       switches also clear pan. Capture is WYSIWYG: a panned preview captures
>       exactly what the preview shows.
>
> **Note (resolved):** a dev-client crash `java.util.zip.ZipException: ZipFile
> invalid LOC header (bad signature)` loading
> `...core.modapi.dto.SchematicDetail$Companion` was a corrupted incremental
> build artifact (the `:core` jar bundled into the fabric jar), not a code bug.
> Fixed by a clean rebuild (`./gradlew clean :core:build :fabric:build`);
> verify with `unzip -t` on `core/build/libs/*.jar` and
> `fabric/build/libs/*.jar` ("No errors detected") if it ever recurs.
> - [ ] Capture → wizard slot shows the thumbnail → upload sends it → web page
>       shows the composed preview (items 70, 72-77).
> - [ ] Without Litematica: wizard still uploads with the placeholder and
>       "Compose preview…" degrades to a notice (item 78).
> - [ ] **No preview ghost, no disappearing build:** the preview renders from
>       a FROZEN SNAPSHOT of the schematic (`SchematicSnapshot` +
>       `SnapshotBlockRenderView`), never from Litematica's live schematic
>       world. "Compose preview…" on a LOCAL FILE source must NOT pop a
>       visible schematic into the world and must NOT add ANY entry to
>       Litematica's placement list (the bridge reads the schematic object
>       directly - the old render-suppressed placement hack is gone, along
>       with the bug where Litematica unloaded that placement's chunks and the
>       build vanished mid-preview). The preview must stay fully populated for
>       as long as the composer is open. Closing the composer changes nothing
>       in Litematica (there is nothing to clean up); user placements / area
>       selections are never touched.
>
> **⚠️ SPIKE GATE (run this FIRST):** Settings tab → **Debug: capture test** →
> confirm `schemat-capture-test.png` appears in the game/run dir and is a
> **non-blank** image of a grass block at an isometric angle on a dark
> blue-grey background. Log lines are prefixed `SCHEMAT-SPIKE`.
> If the PNG is blank/black or the client crashes: the offscreen redirect path
> (`RenderSystem.outputColorTextureOverride`) failed - switch ThumbnailCapture
> to the main-framebuffer-viewport fallback (documented in Task 7) and report
> back. The composer (Tasks 8-9) assumes this spike passes.
>
> Runtime-unverified assumptions in the spike (prime suspects if it fails):
> 1. `outputColorTextureOverride`/`outputDepthTextureOverride` redirect
>    `VertexConsumerProvider.Immediate.draw()` output (OffscreenTarget.bind()).
> 2. `clearColorAndDepthTextures` color int is packed ARGB (OffscreenTarget.clear()).
> 3. Entity render layers sample the lightmap correctly from a GUI click context
>    (block may render dark instead of full-bright).
> 4. `ScreenshotRecorder.takeScreenshot` works on a non-main framebuffer and
>    fires its callback without the target being bound.

60. Spike confirmation as above (gate).

### Fix round 2026-06-11 (post-dev-run): BE crash, blur/aspect, backgrounds

Three issues from the first dev run were fixed; spot-check each:

1. **Block-entity ClassCastException FIXED (root cause, not gated).**
   `BlockEntityRenderManager.getRenderState` is generic in BOTH `E` and `S`
   with `S` not derivable from the arguments; Kotlin inferred `S = Nothing`
   inside the `try { ... ?: continue }` expression and emitted a CHECKCAST to
   `java.lang.Void` → "SignBlockEntityRenderState cannot be cast to
   java.lang.Void" every frame. Now called with explicit type arguments
   (`getRenderState<BlockEntity, BlockEntityRenderState>`). Additionally the
   whole BE pass is failure-contained: per-BE getRenderState/render and the
   final `RenderDispatcher.render()` flush are each try/caught (warn ONCE per
   BE type, prefix `SCHEMAT-CAPTURE`), plus an outer belt-and-braces catch -
   one broken BE renderer can never crash the frame. A
   `RENDER_BLOCK_ENTITIES` flag (default **true**) in
   OffscreenSchematicRenderer can disable the pass entirely if needed.
   - [ ] Compose a preview of a build containing signs + chests: no log spam,
         signs/chests visible. If a specific BE type fails, expect exactly ONE
         `SCHEMAT-CAPTURE` warning for it and the rest of the scene intact.

2. **Blur / aspect FIXED.** The composer's display target was 512² but blitted
   into a larger on-screen rect (GUI-scale pixels), upscaling = blur.
   `DISPLAY_RESOLUTION` is now **1024²** (== CAPTURE_RESOLUTION, so preview ==
   captured PNG), the preview rect stays square, projection aspect stays 1:1
   (ortho cube / `setPerspective(..., aspect=1, ...)`), and the blit texture
   now uses an explicit **clamped LINEAR, non-mipmapped** sampler
   (`SamplerCache.get(FilterMode.LINEAR)`) - downscale-only, V-flip unchanged.
   - [ ] Preview is sharp at GUI scale 2-3 and never stretched; captured PNG
         matches what the preview showed.

3. **Backgrounds per projection (matches the website renderer).**
   - ISOMETRIC → solid **#7ea8ff** clear color. SHIPPED.
   - PERSPECTIVE → **HDRI equirect skybox** SHIPPED: the website's
     `minecraft_day.hdr` was tonemapped (ffmpeg, gamma 2.2) to
     `assets/schematioconnector/textures/hdr/minecraft_day.png` (2048×1024,
     blur+repeat .mcmeta) and is drawn as a fullscreen quad just inside the
     far plane via `RenderLayers.text(id)`, corner UVs computed from the four
     camera-corner view rays (yaw→u seam-unwrapped, pitch→v clamped), flushed
     before the block pass. Any failure in this pass is warn-once and falls
     back to the solid #7ea8ff clear (which is also behind the skybox).
   - Block lighting unchanged (full-bright); this is background only.

### Fix round 2026-06-11 (aspect): capture pipeline is now 16:9

The website stores/displays previews at 16:9 (server crops `preview_image` to
720×405 / 360×202; cards are `aspect-video`), so the old square 1024² capture
was center-cropped server-side, losing ~44% of vertical content. The whole
pipeline is now 16:9 end to end:

- **Capture target:** `CAPTURE_WIDTH=1280` × `CAPTURE_HEIGHT=720`
  (`CAPTURE_ASPECT = 16/9`) replaces `CAPTURE_RESOLUTION=1024`. 1280×720 ≥
  the site's 720×405 medium conversion, and the server crop is now a no-op.
- **Projections:** ISOMETRIC ortho uses half-extents `±s·aspect` horizontally
  and `±s` vertically (s sized from the build radius as before, so tall builds
  still fit top-to-bottom; the frame just widens). PERSPECTIVE keeps the 55°
  VERTICAL FOV with `aspect = 16/9`. The HDRI skybox corner rays use the
  matching `halfX = halfY·aspect` frustum corners so the equirect sky maps
  correctly across the wider frame.
- **Composer:** preview rect is the largest centered 16:9 rect above the
  bottom control bar (usability round below - controls moved off the side
  panel); display target is 1280×720 (== capture target). Rect aspect
  == target aspect == projection aspect → no stretch.
- **Wizard:** the details-step Preview slot and the confirm-card thumbnail
  slot are 16:9 boxes (drawContain still letterboxes mismatched images).
- **Defensive downscale** (>5 MB) is 640×360; the main-framebuffer fallback
  crops the largest centered 16:9 region to 1280×720.
- [ ] Compose + capture + upload: the schematic page / cards on the website
      show the FULL composed framing (no extra crop), not a vertically
      truncated version.
   - [ ] Isometric preview shows flat light-blue background.

### Usability round 2026-06-11: composer auto-fit + maximized preview

The composer was reworked for usability - fixed default distance replaced by
auto-fit framing, side panel replaced by a slim bottom control bar:

- **Auto-fit:** `CameraPose.fit()` / `fitDistance(projection)` snap the
  distance multiplier so the build's bounding sphere fills the frame height
  with ~10% padding (`FIT_PADDING = 1.1`). ISO: `1.1 / ORTHO_EXTENT_FACTOR
  (0.75) ≈ 1.47`; PERSPECTIVE: `1.1 / tan(55°/2) ≈ 2.11`. The factors are
  shared top-level consts in `CaptureModel.kt`, used by BOTH the renderer's
  projection matrices and the fit math, so they cannot drift. Fit runs on
  open, on every preset, and on projection toggle (the presets themselves
  ship pre-fitted). Pose changes still drive the pose-dirty re-render cache.
- **Layout:** the 16:9 preview is now the largest centered rect spanning the
  FULL width above a two-row bottom bar (row 1: Isometric/Perspective
  segments, Front/Iso/Top presets, **Fit**, **Reset**; row 2: status hint
  left - projection · zoom % · controls - and ghost Cancel + emerald Capture
  right).
- **Controls:** orbit sensitivity is normalized to the preview width
  (full-width drag = 220°, identical feel at any GUI scale); scroll zoom is
  exponential (`1.12^notch`, smooth for fractional trackpad deltas); pitch
  clamp ±89° unchanged.
- **Polish:** first offscreen pass is deferred one frame behind a
  "Rendering preview…" placeholder so the screen opens instantly; the
  "large build" warning is overlaid inside the preview's bottom edge on a
  translucent strip.
- [ ] Open composer → build is immediately well-framed (not tiny/clipped) in
      both projections and after each preset.
- [ ] Zoom in/out, press **Fit** → framing snaps back at the same angle;
      status hint shows zoom 100%.
- [ ] Resize window / change GUI scale → preview re-maximizes above the bar;
      drag-to-orbit feel is unchanged.
- [ ] First open shows "Rendering preview…" for at most a frame or two, no
      frozen-open hitch on a 96³ build.
   - [ ] Perspective preview shows the day-sky (darker blue zenith, pale
         horizon); orbiting the camera pans the sky smoothly with no seam at
         the yaw wrap and no stretching; water still blends over the sky.

### Feature round 2026-06-11: background modes, expanded presets, FOV

Website-renderer parity additions to the composer:

- **Background modes (`BackgroundMode`):** a **BG** cycler in row 1 toggles
  - **STUDIO** (default, the original behavior): solid `#7ea8ff` clear for
    ISOMETRIC, HDRI day-sky for PERSPECTIVE.
  - **TRANSPARENT** (the website's transparent PNG export): the framebuffer is
    cleared to alpha 0, the backdrop/HDRI pass is skipped, and the capture
    readback PRESERVES ALPHA → a build-only PNG with real transparency.
    Readback note: `ScreenshotRecorder.takeScreenshot` force-opaques every
    pixel (bytecode-verified `| 0xFF000000` in its pixel loop), so transparent
    captures use `OffscreenTarget.readPngWithAlpha` - the same
    `createBuffer(USAGE_MAP_READ|USAGE_COPY_DST)` →
    `copyTextureToBuffer` → `mapBuffer` → per-pixel `setColor(x, h-1-y, …)`
    flow as vanilla, minus the alpha-force. RUNTIME-UNVERIFIED: dst-alpha
    written by the render pipelines (translucent layers blend alpha too, so
    glass/water alpha may look thin); the flow itself mirrors vanilla 1:1.
  - The preview backs the rect with a subtle checkerboard in TRANSPARENT mode
    (the GUI blit alpha-blends the framebuffer over it), so preview ==
    captured PNG for both modes.
- **Expanded presets (row 1, set follows the projection):**
  - ISOMETRIC: **NW / NE / SW / SE** corner presets (pitch 30°, fitted) - NW
    is the previous "Iso" pose - plus shared **Top** (isometric top-down 89°).
  - PERSPECTIVE: **Front / Back / Left / Right** faces (pitch 5°, fitted) plus
    **Top**. Compass naming: yaw 0 = camera north of the build; yaw increases
    counter-clockwise (45 NW, 135 SW, 225 SE, 315 NE).
  - Every preset auto-fits, keeps the user's FOV, and rebuilds the control row
    (`clearAndInit`) since Top switches the projection.
- **FOV cycler (PERSPECTIVE only):** 35/45/55/70°, default 55
  (`CameraPose.fovDeg`, fed into the projection matrix, HDRI frustum corners
  AND the fit/pan math, so auto-fit stays exact at any FOV).
- [ ] BG: Transparent → preview shows the build over a grey checkerboard
      (orbit: the checker shows through everywhere the build isn't); BG:
      Studio → flat blue (iso) / day-sky (perspective) as before.
- [ ] Capture with BG: Transparent → upload (or save) → the PNG has REAL
      alpha (e.g. open in an image editor / the website shows page background
      through it); no #7ea8ff or sky pixels anywhere.
- [ ] Iso NW/NE/SW/SE show the four corners (build rotates 90° between
      adjacent presets); perspective Front/Back/Left/Right likewise; Top is a
      top-down view and flips the projection segment to Isometric.
- [ ] FOV cycle 35→45→55→70 visibly changes perspective foreshortening while
      the build stays fitted; the button label tracks the value; switching to
      Isometric hides the FOV button, switching back restores it.
- [ ] Pose-dirty cache: toggling BG or FOV or clicking any preset re-renders
      the preview exactly once; an idle pose re-blits without an offscreen
      pass.

### Render source (Task 5 - compile-verified only)

Runtime-unverified assumptions in `SchematicRenderSource` /
`LitematicaBridge.loadRenderSource`:

The preview now renders from a FROZEN SNAPSHOT (`SchematicSnapshot` captured
once on the client thread, wrapped in a self-contained
`SnapshotBlockRenderView`) instead of Litematica's live `WorldSchematic`. No
`SchematicPlacement` is ever added for a preview: no in-world ghost, and no
"build disappears" (the old render-suppressed placement's chunks got unloaded
by Litematica mid-preview).

1. **Placement / local-file source:** the bridge reads the
   `LitematicaSchematic` OBJECT (the placement's `getSchematic()`, or
   `SchematicHolder.getOrLoad(Path)` for a file) - sub-region states via
   `getSubRegionContainer(name).get(x,y,z)`, block entities via
   `getBlockEntityMapForRegion(name)` NBT. Sub-region offsets use the
   litematic SIGNED-size normalization (min corner = min(pos, pos+size∓1));
   verify a MULTI-REGION schematic previews with its regions in the right
   relative spots. Placement rotation/mirror is NOT applied - the preview
   shows the schematic as authored.
2. **Frozen means frozen:** the preview must stay fully populated no matter
   how long the composer sits open, and previewing must not touch
   Litematica's placement list at all (Litematica GUI shows no new entry).
3. **Area selection source:** the REAL client world is copied (states + live
   BEs) over the selection's box union at open time - verify the preview
   shows the world blocks and does NOT change if you edit the world while the
   composer is open (it's a copy).
4. **Block entities from NBT:** instantiated via
   `BlockEntity.createFromNbt(pos, state, nbt, world.registryManager)` with
   the BE's world set to the client world; failures are skipped (debug log).
   Verify chests/signs/beds from a schematic render in pass 3 - a BE whose
   renderer needs a real world attachment is the main runtime suspect.
5. **Lighting/tint in the snapshot view:** full-bright (light level 15,
   vanilla directional face shading; `LightingProvider.DEFAULT` only
   satisfies the interface) and grass/leaves/water tint resolve against a
   captured PLAINS biome entry (white fallback if no world). Verify blocks
   are not black and tint is plausible - if tint looks wrong, suspect
   `getColor`'s plains entry; if BEs are dark, the forced BE lightmap.
6. Large build (> 96 blocks in any dimension) sets `downsampled = true` →
   composer shows the "large build" note. VOLUME_CAP is ENFORCED, not just a
   warning: all three passes (blocks, fluids, block entities) iterate a
   centered sub-region clamped to 96 per axis (`renderMinPos`/`renderMaxPos`,
   worst case ≈ 884k cells) - snapshot CAPTURE is bounded by the same
   `clampedRenderRegion` helper, so huge builds also snapshot at most that
   sub-region - and `center()`/`radius()` frame that sub-region
   so the camera isn't pulled back to fit unrendered space. Verify: a >96³
   placement previews the build's CENTER region, stays responsive, and the
   warn note appears. Note the offscreen pass is also pose-dirty cached - the
   composer only re-renders when the pose changes (first frame, drag, scroll,
   preset, projection toggle); a static pose re-blits the cached framebuffer,
   so even the 884k worst case costs one pass per pose change, not per frame.

### Offscreen renderer (Task 6 - compile-verified only)

`OffscreenSchematicRenderer` runs three isolated passes (blocks → fluids →
block entities); a broken pass should only lose its own geometry. Backdrop is
a SOLID clear (0xFF1A1A22) for now - gradient is a TODO. Per-pass runtime
suspects:

1. **Pass 1 blocks - lighting:** `renderBlock` derives light from
   `source.view` - now `SnapshotBlockRenderView`, which is hardwired
   full-bright (`getLightLevel`/`getBaseLightLevel` → 15) with vanilla
   directional face shading in `getBrightness`. If blocks come out BLACK,
   suspect a light path that goes through `getLightingProvider()` (we return
   the no-op `LightingProvider.DEFAULT`) instead of the level getters.
2. **Pass 1 blocks - layer choice:** 1.21.11's
   `BlockRenderLayers.getBlockLayer` returns a chunk-layer ENUM unusable with
   `Immediate.getBuffer`; we use `getMovingBlockLayer(state): RenderLayer`
   (the piston/falling-block path). Verify leaves/glass/translucents look
   right (no missing cutouts, no opaque water-glass).
3. **Pass 2 fluids - section-local coords:** `FluidRenderer` emits vertices
   at `pos & 15` (bytecode-confirmed) and ignores the MatrixStack, so the
   pass wraps the buffer in a CPU-transforming VertexConsumer positioned at
   the section origin. If fluids appear OFFSET (snapped to weird 16-block
   grid positions) or missing, this wrapper/origin math is the suspect.
4. **Pass 2 fluids - layer mapping:** the `BlockRenderLayer` enum →
   `RenderLayer` map (TRANSLUCENT→translucentMovingBlock, CUTOUT→cutout,
   SOLID→solid, TRIPWIRE→tripwire) is hand-rolled; verify water is
   translucent and lava is opaque.
5. **Pass 3 block entities - command queue:** BEs go through the new
   1.21.11 flow (`BlockEntityRenderManager.getRenderState(be, 0f, null)` →
   `render(state, matrices, queue, cameraState)` → our own reused
   `RenderDispatcher.render()`). The dispatcher is built on the client's
   shared vertex consumers and intentionally never closed. Suspects: null
   crumbling-overlay arg, an unconfigured `CameraRenderState` (approximated
   eye pos/identity orientation - sign TEXT may face the wrong way), and
   whether a private RenderDispatcher coexists with the client's own.
   Chests/signs/beds are the test cases. BE lightmap is forced full-bright.
6. **Camera/projection:** ortho frame `s = radius·distance·0.75` and
   perspective FOV 55° are first guesses - verify the build fills the frame
   at default poses (ISO/FRONT/TOP) and yaw sign matches drag direction
   (orbit may feel inverted; flip `pose.yaw` sign in the renderer if so).
7. **Isolation check:** if the whole image is backdrop-only, re-run the spike
   (§14 gate) first - output redirect, not the passes, is then the suspect.

### Capture orchestration (Task 7 - compile-verified only)

`ThumbnailCapture` wires the renderer + target into the upload flow.

61. **Capture round-trip:** from the composer (Task 8), trigger a capture →
    expect a `CaptureResult.Success` whose PNG bytes decode to the same image
    as the live preview. Failures log with prefix `SCHEMAT-CAPTURE` and
    surface as `CaptureResult.Failure` with a readable message. The pooled
    1280×720 (16:9) target is reused across captures (don't spam-click capture - the
    readback is async ~1 frame and a second render could overwrite pixels
    mid-read; the composer should disable the button while one is in flight).
62. **Display blit (RUNTIME-UNVERIFIED - flag for Task 8):**
    `ThumbnailCapture.drawTargetInto` draws the offscreen framebuffer into a
    GUI rect by registering the color attachment as a no-close
    `AbstractTexture` under `schematioconnector:thumbnail/preview` and calling
    `DrawContext.drawTexture(GUI_TEXTURED, id, x, y, u=0, v=texH, w, h,
    regionW=texW, regionH=-texH, texW, texH)` - the negative region height
    encodes the bottom-up→top-down V-flip (v1=1, v2=0, the same UVs vanilla's
    `SpecialGuiElementRenderer` uses for its framebuffer blit). The vanilla
    path proper (`TexturedQuadGuiElementRenderState`) is unreachable without
    an access widener (`DrawContext.state` / `GameRenderer.guiState` are
    package-private). **If the preview rect is blank, garbled, or
    upside-down:** (a) upside-down → drop the flip (use `v=0, regionH=+texH`);
    (b) blank → the wrapper-texture registration is the suspect; add an
    access widener for `net/minecraft/client/gui/DrawContext` field `state`
    and submit a `TexturedQuadGuiElementRenderState` mirroring
    `SpecialGuiElementRenderer.renderElement`.
63. **5 MB cap:** a 1280×720 PNG should be far under the API's 5 MB preview cap;
    if not, `ThumbnailCapture` downscales to 640×360 (still 16:9) via
    `NativeImage.resizeSubRectTo` and re-encodes (watch for a
    `SCHEMAT-CAPTURE ... downscaling` warn - its presence at all is unusual).
64. **Main-framebuffer fallback (only if the §14 spike gate FAILS):** set
    `ThumbnailCapture.USE_MAIN_FRAMEBUFFER_FALLBACK = true` and rebuild.
    `capture(...)` then screenshots the MAIN framebuffer of the current frame
    (assumes the composer just drew the preview - it renders every frame) and
    crops the largest centered 16:9 region to 1280×720 (Litematica
    PreviewGenerator precedent, adapted to the 16:9 pipeline).
    v1 stub: it does NOT issue its own render passes into a viewport rect;
    if the offscreen path is dead AND the fallback crop framing is wrong,
    that viewport-render step is the remaining work.
65. **Teardown:** closing the composer should call
    `ThumbnailCapture.releasePooledTarget()` - no GPU leak of the 1280×720
    framebuffer across composer sessions (re-opening recreates it).

### Composer screen (Task 8 - compile-verified only)

`ThumbnailComposerScreen(parent, source, onCaptured)` - the interactive studio
that feeds the upload wizard. All interactions below are runtime-unverified.

66. **Layout:** opaque scrim, title centered at top, a large centered 16:9
    preview (bordered via `UiPanels.drawPanel` ring) left of a 110px controls
    strip (Isometric/Perspective toggle, Front/Iso/Top presets, Capture,
    Cancel); hint "Drag to orbit · Scroll to zoom" under the preview; a
    "Large build - preview may omit detail" warning when the source was
    down-sampled (any dimension > 96). Controls strip never overlaps the
    preview rect.
67. **Live preview (pose-dirty cached):** the schematic is rendered into a
    private 1280×720 `OffscreenTarget` (`renderToFramebufferForDisplay`) ONLY when
    the pose changed since the last offscreen pass (first frame, drag, scroll,
    preset, projection toggle); every frame the (possibly cached) framebuffer
    is blitted via `drawTargetInto`. Verify the preview updates immediately on
    drag/scroll/preset and does NOT cost an offscreen pass while idle. If the
    rect is blank/garbled/upside-down, see item 62 - the blit is the prime
    suspect, not the composer.
68. **Orbit:** click-drag that STARTS inside the preview rect orbits
    (`pose.orbit(dx*0.5, -dy*0.5)`, pitch clamped ±89°); drags starting on
    widgets do not orbit. **Zoom:** scroll while hovering the preview
    (scroll up = zoom in, distance clamped 0.6-6×).
69. **Projection toggle:** button label shows the CURRENT projection and
    flips it in place; presets reset the full pose (Front = perspective,
    Iso/Top = isometric) and the toggle label follows.
70. **Capture:** disables all controls, shows a "Capturing…" spinner over the
    preview, runs `ThumbnailCapture.capture`; on success the screen returns
    to `parent` FIRST and then invokes `onCaptured(pngBytes)` (TagSelector
    idiom - the callback may navigate). On failure a red NoticeBanner shows
    the message and controls re-enable. Esc is blocked while capturing
    (`shouldCloseOnEsc = false`). The success/failure handler checks
    `currentScreen === composer` - if the screen was replaced externally
    (disconnect, other mods) mid-readback, the result is dropped silently
    instead of yanking navigation.
71. **Teardown:** `removed()` unregisters the preview wrapper texture, closes
    the 1280×720 display target, and REQUESTS release of the pooled 1280×720 target
    (`releasePooledTarget()`). ThumbnailCapture owns the pooled target: if a
    capture readback is in flight when the screen is removed (external
    replacement), the actual close is deferred to the readback callback - no
    free-mid-readback. Verify no framebuffer leak across repeated open/close
    cycles. Cancel and Esc both restore `parent`. `shouldPause()` is false.

### Wizard integration (Task 9)

The upload wizard's details step now has a **Preview** slot (left column, under
Community) and a **"Compose preview…"** button. The composer's parent is the
wizard instance itself, so all field state survives the round-trip.

72. **Slot + button:** details step shows the Preview label, a dark "No preview
    yet" box, and "Compose preview…" (disabled only while a render source is
    loading). Clicking it resolves the selected source via
    `loadRenderSource` and opens the composer; while resolving the slot reads
    "Loading schematic…".
73. **Round-trip state:** fill in name/description, pick tags + co-authors,
    THEN compose a preview and capture. Returning to the wizard must show the
    same details step with name, description, visibility, community, tag
    chips, and co-authors all intact, the slot now showing the captured
    thumbnail (aspect-fit, letterboxed), and an INFO banner "Preview
    captured". Cancelling the composer (Esc or Cancel) returns the wizard
    equally intact with the slot unchanged.
74. **Re-compose:** capturing again replaces the slot image (old texture is
    destroyed - no stale image, no GPU leak across repeated captures).
75. **Source switch:** going Back to the source step and selecting a DIFFERENT
    source clears the captured preview (slot returns to "No preview yet");
    re-selecting the same source keeps it.
76. **Upload uses the capture:** the confirm step's review card shows the
    composed thumbnail in its bordered slot (a "No preview" placeholder box
    when none was captured) and the uploaded schematic's web page shows the
    composed thumbnail, not the initials-placeholder. Without a capture, the
    placeholder image behaves exactly as before.
77. **Litematica GUI entries:** Litematica's load GUI ("Upload to schemat.io"
    on a selected file) and the placements list both open this same wizard
    pre-seeded with a LOCAL_FILE / PLACEMENT source - verify "Compose
    preview…" works from both entry points (the local-file path may create a
    temporary placement, see Render source item 4).
78. **No-Litematica degradation:** without Litematica installed the button
    shows the red notice from the bridge ("…requires Litematica") and the
    upload proceeds with the placeholder. WorldEdit-clipboard sources show
    "Previews are not supported…" - also placeholder, never a crash.

## 15. Upload-experience polish (website-look pass - compile-verified only)

Echoes the website's palette in-game: opaque dark cards (`0xFF181820`) with
1px borders, the fuchsia accent (`#db45f0`) on active controls, uppercase
muted labels (`#7d7d85`), and the emerald publish CTA (`#34D399`, black text).

### Composer control card (`ThumbnailComposerScreen`)
79. The right-hand controls are now a single opaque card with a dim-accent
    border, grouped under uppercase labels:
    - **PROJECTION** - a 2-segment toggle **Isometric | Perspective**; the
      ACTIVE segment is filled accent fuchsia with white text, the inactive
      one is a dark well with muted text. Clicking a segment switches the
      projection immediately (both options always visible, no cycling).
    - **VIEW PRESETS** - small **Front · Iso · Top** buttons on one row, plus
      a full-width **Reset view** that returns to the default ISO pose.
    - A muted two-line hint: "Drag to orbit" / "Scroll to zoom".
    - **Capture** as the prominent emerald CTA (black text) and a muted
      **Cancel** below it.
80. The 16:9 preview stays large and never overlaps the card. While
    capturing, every control in the card is disabled and the "Capturing"
    spinner renders over the preview center. Orbit/zoom/presets/capture/
    cancel behavior is unchanged (incl. Esc being blocked mid-capture).

### Confirm step review card (`UploadWizardScreen`, step 3)
81. Step 3 renders one clean card: the composed thumbnail on the left in a
    bordered 16:9 slot ("No preview" placeholder when none), with a muted
    "source · format" caption under it; on the right, uppercase-label rows -
    **NAME** (bold white), **DESCRIPTION** (plain text, wrapped, max 3
    lines), **VISIBILITY** + **COMMUNITY** side by side, **TAGS** (chips, or
    "None"), **CO-AUTHORS** (head avatar + name per row, "+N more" when
    cut off, or "None").
82. The **Upload** nav button is the emerald primary CTA on this step
    (Cancel/Back stay vanilla). While uploading/exporting the CTA is hidden
    and replaced by an "Uploading" spinner in its place; on success the wizard
    closes to the game and the clickable chat notice appears (see step 16), and
    validation failures still jump back to the details step with field errors
    in the banner.

### Co-author head avatars (`HeadAvatarManager`)
83. Co-author rows in the details-step editor AND the confirm card show the
    player's Minecraft head (12px) left of the name; a neutral grey box
    renders while the avatar loads. API-sourced authors (edit screen) use
    their `head_url`; players added by name fall back to
    `https://mc-heads.net/avatar/{uuid}/64`.
84. Avatars are cached in a shared LRU (cap 64) on `ClientServices`; evicted
    textures are destroyed on the render thread. Failed lookups (offline,
    unknown uuid) just keep the placeholder box - no crash, no retry storm.

## 16. UI fix round 2026-06-11 (themed inputs + cyclers - compile-verified only)

### Typed text visible in all themed inputs (`ThemedTextField`, browse search)
85. Type in every `ThemedTextField` - upload-wizard Name, quick-share Name /
    Password / Max uses, tag-dialog name/color, community search, co-author
    name, int/float tag-filter values - typed characters render as white
    (`TEXT_PRIMARY`) text inside the SURFACE_ALT well, cursor visible,
    placeholder faint. (Root cause: colors passed to the 1.21.6+
    `DrawContext.drawText` are dropped entirely when alpha is 0; the fields
    set `TEXT_PRIMARY and 0xFFFFFF`, stripping alpha. Now full ARGB;
    uneditable state uses `TEXT_MUTED`.)
86. Browse-tab search field (raw `TextFieldWidget` in the themed well) shows
    typed text the same way. Description `EditBoxWidget`s (upload wizard +
    edit screen) were already fine: they draw their own opaque-default text
    and the themed well is only a stroke ring drawn around them, never a fill
    over them.

### Remaining vanilla cyclers themed
87. Upload-wizard Community picker is now a `FlatButton.secondary` cycler
    labelled "Community: <name>" / "Community: None" (no vanilla button
    texture). Clicking steps through None + joined communities; selecting a
    different community still drops its checked community tags (minecraft
    tree kept), prunes filter values, and reloads community tags; upload
    still sends the selected community id (omitted for None).
88. Quick-share Expires picker is the same FlatButton cycler pattern; the
    label shows the expiry option text and `expirySeconds` updates per click.
    No `CyclingButtonWidget` (default MC skin) remains anywhere in the
    client UI.

## 17. Rich-text descriptions (display + editor - compile-verified only)

Descriptions are stored as HTML on the website; the mod now renders the basic
formatting and edits it through a markup editor (core `RichText`:
`htmlToSpans` / `markupToSpans` / `markupToHtml` / `htmlToMarkup`).

### Display renders formatting

89. Browse → open a schematic whose description was authored with the website
    editor (bold/italic/underline/strikethrough, paragraphs, a bullet list).
    The detail screen's Description block shows **bold**, *italic*,
    underline and strikethrough styled (not stripped), paragraphs separated
    by a blank line and list items prefixed with `• `. Long lines word-wrap
    in the info column; overflow past the banner is clipped as before.
90. Malformed/plain descriptions still render (plain text unchanged, no
    crash on stray `<` or unclosed tags).

### Description editor (Upload wizard details step + Edit screen)

91. The plain description box is replaced by the rich editor: a **B I U S**
    ghost-button toolbar (styled labels), the markup input well, a faint
    "wraps selection" hint, and a bordered PREVIEW well underneath.
92. Typing `**bold**`, `*italic*`, `__underline__`, `~~strike~~`, blank
    lines and `- ` bullet lines updates the preview live with the styled
    rendering.
93. Select text in the input and click B/I/U/S: the selection is wrapped in
    the matching markers (via the `EditBoxWidgetAccessor` mixin →
    `EditBox.replaceSelection`). With no selection an empty marker pair is
    inserted at the cursor. If clicking the button cleared the selection
    (focus quirk), the markers land at the cursor - acceptable fallback.
94. Upload wizard: the confirm step shows the styled description preview
    (first ~3 lines) and the uploaded description arrives on the website
    with `<p>/<br>/<strong>/<em>/<u>/<s>/<ul><li>` only (sanitizer
    allowlist) - check the schematic page renders identically to a
    website-authored description.
95. Edit screen: opening a schematic loads the existing HTML into the editor
    as markup (`htmlToMarkup`) with formatting preserved; saving without
    touching the description omits it from the update (no lossy rewrite);
    after editing, Save sends `markupToHtml` and the website shows the
    formatting. Round-trip: load → save unchanged → description HTML on the
    website is unchanged.

## 18. /schematio subcommands (compile-verified only)

The bare `/schematio` command now carries a Brigadier subcommand tree
(`SchematioClientCommands`); previously any argument errored with
"Incorrect argument for command".

96. `/schematio` (no args) - still opens the Home screen (deferred via
    `client.send` so the closing chat screen doesn't swallow it).
97. `/schematio open` and `/schematio browse` - same as the bare command.
98. `/schematio upload` - opens the upload wizard (skips to Details when a
    Litematica selection/placement is current, like the Home button).
99. `/schematio quickshare` - opens the quick-share creation screen.
100. `/schematio download <id>` - accepts a schematic short id, uuid or
     slug; downloads `.litematic` bytes via `POST /schematics/{id}/download`,
     writes them into Litematica's `schemat.io` schematics subfolder
     (or `<game>/schematics/schemat.io` without Litematica) and creates a
     placement. Chat feedback: "Downloading …" then "Loaded … into
     Litematica" / red error (e.g. not found). Without Litematica the file
     is saved and the chat notice points at the path.
101. `/schematio quickshareget <accessCode> [password]` - e.g.
     `/schematio quickshareget qs_re3LyVO5Dn`. Must PARSE (no "Incorrect
     argument") - codes are `word()`-safe. Downloads through the same
     download route (the backend resolves quick-share access codes there);
     `password` (greedy string) is sent for protected shares. Wrong/missing
     password → red error in chat.
102. `/schematio help` - prints the command list to chat.
103. Tab-completion suggests the literals (open/browse/upload/download/
     quickshareget/quickshare/help) after `/schematio `.
