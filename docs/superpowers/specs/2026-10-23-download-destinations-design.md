# Download destinations: priority routing between WorldEdit and Litematica

## Why

On a server running the Schematio Connector and WorldEdit, a player with the
client mod and Litematica typed `/schematio download 44srfK`. The client's own
`/schematio` command handled it and loaded the schematic into Litematica. The
server never saw it, so it never reached the player's WorldEdit clipboard.
Three gaps cause this:

1. The Fabric server build of the Connector registers `/schematio`, but it never
   announces itself on the `schematio:c` channel. Only the Paper plugin sends
   HELLO_SERVER. So the client thinks the server has no Schematio and handles
   every `/schematio` command locally.
2. The client decides where a download goes with a hard-coded rule:
   `DispatchTable` forwards to the server only when a plugin is present.
   Players have no say in it and can't override it.
3. The schematic detail panel offers "Load on server" only when the session is
   VERIFIED (backend-attested). On most servers, including a Fabric server,
   only "Load into Litematica" is shown.

The user wants WorldEdit to win by default, server-side mods to win over local
ones, a priority order that can be set per server and for singleplayer, and
flags to choose a destination for a single command.

## Goals

- `/schematio download <id>` and `/schematio quickshareget <code> [password]`
  send the schematic to the first available destination in the player's
  priority list. The default list is:
  1. **Server WorldEdit**: the server-side Connector (Paper plugin or Fabric
     server mod) loads it into the player's WorldEdit clipboard on the server.
  2. **Local WorldEdit**: singleplayer or a LAN host, through the existing
     client `WorldEditBridge`.
  3. **Local Litematica**: a placement through the existing `LitematicaBridge`.
  4. **Save to disk**: always available.
- Each world has its own priority list: one per multiplayer server, keyed by
  the address in the server list, and one for singleplayer. A world without its
  own list uses the default order.
- Per-command flags force a single destination:
  - `--worldedit` / `--we`: server WorldEdit, else local WorldEdit.
  - `--litematica` / `--lm`: local Litematica.
  - `--file`: save to disk.
  - `--server` / `--local` pick a side, and combine with a tool flag (for
    example `--local --we`).

  Flags are parsed on the client and never forwarded to the server.
- Fallback without a flag: skip unavailable destinations. If the result is not
  the list's first entry, say so in chat, e.g. "No Schematio on this server.
  Loaded into Litematica instead."
- Fallback with a flag: never fall back. If the forced destination isn't
  available, fail with the reason, e.g. "This server has no Schematio
  Connector with WorldEdit."
- The detail panel shows one load button per available destination, in
  priority order. The first one is the highlighted primary button.
  "Load on server" becomes "Load into WorldEdit (server)".
- The Fabric server Connector announces itself with HELLO_SERVER, including
  whether WorldEdit is installed.
- `/schematio download` on the client accepts schemat.io schematic URLs as well
  as IDs. Today a URL gives a client-side syntax error.

## Non-goals

- **Server Litematica** (Syncmatica-style shared placements, Servux, or
  server-side pasters). Litematica is client-only, and each server-side
  companion is a separate integration. The destination model below must make
  one easy to add later as another `(SERVER, LITEMATICA)` entry.
- Attestation (ATTEST), LOAD_REQUEST and UPLOAD_CLIPBOARD on the Fabric server.
  The Fabric server only needs HELLO_SERVER for this work.
- Routing uploads or quick-share creation. `upload` and `quickshare` keep their
  current behavior.
- Changing how the Paper plugin routes a command it receives. Its 404 wording
  does change (see Private schematics).

## Anti-goals (a failure even if it works)

- A forced destination silently loading somewhere else.
- Any silent failure. Every failure tells the player and, on the server, logs
  to the console (the 1.5.1 standard).
- Sending a player's schemat.io credentials to a server. Server destinations
  carry only the schematic ID or access code, as typing the command would.
- The client handling a download that the player meant for the server, after
  the server's Schematio has been detected (the original bug).
- Breaking released Paper plugins (1.5.x) or vanilla clients.

## How it works

### Destination model (core, pure Kotlin, unit-tested)

```
enum Side { SERVER, LOCAL }
enum Tool { WORLDEDIT, LITEMATICA, FILE }
data class Destination(side, tool)
```

A resolver takes:
- the priority list,
- a parsed flag filter (side and/or tool, or none),
- an availability snapshot: which destinations exist right now.

It returns either the chosen destination, plus whether it was a fallback, or a
failure with a player-facing reason. It has no Minecraft dependencies, so
`core` can test every combination.

### Availability on the client

- **Server WorldEdit** is available when:
  - this is a multiplayer connection, not singleplayer;
  - `ServerSession.pluginPresent` is set;
  - and the server says WorldEdit is present. That means the new capability
    bit `SERVER_WORLDEDIT`, or `LOAD_CLIPBOARD` from Paper plugins released
    before the bit existed.
- **Local WorldEdit**: `Bridges.worldEdit.isAvailable`. It is active only in
  singleplayer or on a LAN host.
- **Local Litematica**: `Bridges.litematica.isAvailable`.
- **File**: always available.

In singleplayer, the Connector's server side runs in the integrated server, and
it would send HELLO_SERVER too. The integrated server never sends HELLO_SERVER,
to its own player or to LAN guests (`server.isDedicatedServer` gate). Today's
singleplayer and LAN behavior stays the same for every command, not only
downloads. Only the LOCAL entries count there.

`DispatchTable` keeps forwarding UPLOAD and QUICKSHARE only to Paper servers
(`platform == PAPER`, or a pre-v2 hello with no platform, which is always
Paper). On a Fabric server they keep running on the client, as today. Its
server-side `upload` needs op and the server's token, so forwarding would be a
regression.

**"Server WorldEdit" must mean "this server will load it for you".** Both
servers compute `SERVER_WORLDEDIT` **per player** when they send HELLO_SERVER,
and set it only when all of these hold:
- WorldEdit is loaded;
- an API token is configured;
- `download` is not in the disabled commands;
- on Paper, the player has `schematio.download`.

The hello is sent again after `/schematio reload` and `settoken`, so a change
reaches players who are already online. A Paper plugin from before this change
sends LOAD_CLIPBOARD without that gating, which is acceptable for old servers.

**Failure after choosing server WorldEdit:**
- On the LOAD_REQUEST path the client sees the final STATUS. A non-OK status
  with no flag given falls back to the next destination and says why in chat.
  With a flag, it fails with that reason.
- On the forwarded-command path the client can't see the result, so there's no
  fallback. The server's own chat reply is the feedback. That's acceptable
  because the hello already reported the server's readiness for this player.

### Executing "server WorldEdit"

- **The session is VERIFIED and the server advertises LOAD_CLIPBOARD:** use
  the existing LOAD_REQUEST flow (`ServerIpc.sendLoadRequest`, with STATUS
  tracking). The panel gets progress, and the command path reports the final
  STATUS in chat.
- **Otherwise:** forward exactly `schematio download <id>` with the existing
  `forwardToServer`. For quick shares the command depends on the server's
  platform:
  - Paper: `schematio quickshareget <code> [password]`.
  - Fabric: `schematio download <code> [password]`, because the Fabric server
    has no `quickshareget`. Its `download` treats `qs_…` input as a quick
    share, so add the `qs_` prefix when the player left it off. Check the
    prefix normalization against `InputValidator.validateQuickShareCode`. The command is sent as an unsigned command packet, so the
  server runs its normal command path, which is what a vanilla client gets.
  The server's chat replies are the feedback.
  - Don't append flags. Released Paper plugins read the second download
    argument as a format, so a flag would break them.
  - A forwarded plain command is safe from the Paper OPEN_UI handoff. The
    handoff needs an attested session, and attested sessions with
    LOAD_CLIPBOARD take the LOAD_REQUEST path above.
  - Edge case: a VERIFIED session without LOAD_CLIPBOARD (a Paper server
    without WorldEdit) has no server WorldEdit, so nothing is forwarded.

`DispatchTable` stops deciding DOWNLOAD and QUICKSHARE_GET. The resolver does.
UPLOAD, QUICKSHARE and BROWSE keep their current dispatch.

### Private schematics (backend + both servers)

Server WorldEdit is always tried for private schematics too. There's no
client-side public/private pre-check.

**Backend rule (Schem-at/schemati, `SchematicController::communityTokenCanRead`):**
a community token may also read a schematic when both of these hold:
- the request's `player_uuid` resolves to a player who is a member of the
  token's community (`Community::hasMember`);
- that player can view the schematic with their own account
  (`TagVisibilityResolver::canView` with the player's user).

Joining a community counts as the member's consent for that community's
servers to fetch their schematics for them. This is the user's rule.
- Only `show` and `download` use it. `update` and `destroy` stay limited to the
  community, as in PR #107.
- Feature tests cover four cases:
  - a member's own private schematic → 200;
  - the same schematic requested for a non-member → 404;
  - a member requesting a private schematic they can't view → 404;
  - a public schematic outside the community → 200 (unchanged).
- Accepted risk, stated plainly: a community's server can fetch any schematic
  that one of its members can view, by naming that member. That is the consent
  model the user chose.

**Explicit not-found message on both servers:** when the download returns 404,
the server tells the player:

  "Not found (404). Check the ID. If the schematic is private, its owner must
  join the <community> community on schemat.io for this server to load it."

It uses the community name when the server knows it, and "this server's"
otherwise. The 404 is also logged, per the 1.5.1 standard. On the client's
LOAD_REQUEST path the STATUS detail carries the same text.

### Fabric server handshake

The Fabric server registers the `schematio:c` payload for both directions in
the common/main initializer. Today only the client source set registers it.
Then:
- On join (or on the client's HELLO_CLIENT, matching what Paper does) the
  server sends HELLO_SERVER. It reports protocol version, Connector version,
  platform FABRIC, MC version, backend host and community, and these
  capabilities:
  - `DOWNLOAD_CMD`;
  - `SERVER_WORLDEDIT` when the `worldedit` mod is loaded.

  It does **not** advertise LOAD_CLIPBOARD, UPLOAD or WANTS_COMMAND_OWNERSHIP
  handling, and never sends ATTEST. Without ATTEST, the client trust state
  becomes UNVERIFIED, and every attestation-gated feature stays off.
- Opcodes the Fabric server doesn't implement are logged at debug and ignored.

The Paper plugin also advertises `SERVER_WORLDEDIT` when WorldEdit is present.
That's a one-line change in `capabilitiesFor`. Keep `LOAD_CLIPBOARD` as it is.

### Priority storage and settings

- Profiles are stored in the client config. The keys are:
  - `default`;
  - `singleplayer`;
  - `server:<host[:port] lowercased>`, from the current `ServerData` address.

  Each value is an ordered list of destinations. A missing profile falls back
  to `default`. A stored list missing a destination (from a future version)
  gets the missing ones appended in default order.
- Settings panel, new "Download destination" section:
  - It shows the list for the current world (this server / singleplayer), with
    a note when that world is using the default.
  - Each row has move-up and move-down controls.
  - "Reset to default" deletes the per-world profile.
  - It also edits the default list.
- Each row shows whether the destination is available right now, and why not
  when it isn't, e.g. "Server WorldEdit: this server has no Schematio".

### Command and panel changes on the client

- `download` takes a greedy argument. Parse the ID or URL, plus flags, from
  it. `quickshareget` gets the same flags; the password stays the last
  non-flag token.
- The help text and the detail-panel button labels name the destinations.
- Chat feedback always names where the schematic went: "Loaded into your
  WorldEdit clipboard on the server", "Loaded into Litematica", "Saved to …".

## Constraints

- Every supported Minecraft target (1.21.8 to 26.3) on Fabric. Stonecutter
  conditionals follow the existing networking pattern in `ServerIpc`
  (`PayloadTypeRegistry` was renamed in 26.1).
- The IPC protocol stays backward compatible:
  - The new capability bit is additive.
  - A v1/v2 client talking to a new Fabric server just sees more HELLO_SERVER
    traffic than before.
  - A new client talking to an old Fabric server falls back to local
    destinations, as it does today.
- Tests:
  - Resolver and profile-merge logic in `core` with JUnit.
  - Fabric server HELLO_SERVER checked headlessly with the schematio0 harness.
    mineflayer can register `schematio:c` and decode the payload with the core
    codec's layout.
  - Client routing can't be driven by mineflayer. Cover it with unit tests on
    the client-side decision code (availability snapshot to command action),
    and say in the release notes that the routing itself was not tested in a
    real client.

## Decided by the user

- WorldEdit takes priority over Litematica.
- Server-side destinations take priority over local ones.
- The priority order can be configured per server and for singleplayer.
- Flags pick a destination for one command.
- Private schematics: membership in the server's community is consent for that
  community's server to fetch the member's schematics. Otherwise the server
  tries and fails with an explicit 404 that suggests joining the community.

## Decided by Claude (check these)

- No "server Litematica" destination in this version (see Non-goals).
- With no flag, fall back with a chat note; with a flag, fail instead of
  falling back.
- Server WorldEdit uses the verified LOAD_REQUEST when possible, otherwise a
  plain forwarded command. It does not require VERIFIED.
- The integrated server (singleplayer and LAN host) never sends HELLO_SERVER,
  so nothing changes there.
- UPLOAD/QUICKSHARE forwarding stays Paper-only.
- `SERVER_WORLDEDIT` is computed per player: WorldEdit + token + `download`
  enabled + permission.
- On the LOAD_REQUEST path a failed server load falls back when there's no
  flag. On the forwarded-command path there's no fallback.
- Fabric servers get quick shares as `download qs_<code>`.
- Profiles are keyed by server address, not by server name.
- The client accepts schemat.io URLs in `download`.

## Left to the builder

- Flag spellings beyond the ones listed, and exact chat wording.
- Settings UI layout, within the panel's existing ImGui style.
- Where profiles live in the client config (the existing key/value config, or
  a small JSON file next to it).
- Whether HELLO_SERVER is sent on join, on HELLO_CLIENT, or both. Match
  whatever the client's `markHelloSent` logic expects.
