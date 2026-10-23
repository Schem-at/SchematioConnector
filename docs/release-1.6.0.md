# SchematioConnector 1.6.0

You now choose where `/schematio download` loads a schematic. WorldEdit comes before
Litematica, and the server comes before your own client.

Before 1.6.0, a player with Litematica installed who typed `/schematio download` on a
server running WorldEdit had the command picked up by the client mod and loaded into
Litematica. A Fabric server running Connector never announced itself to the client, so
the client didn't know the server could load into WorldEdit.

## Download destinations

There are four destinations:
- the server's WorldEdit;
- your own WorldEdit;
- Litematica;
- a file on disk.

The default order is the one in that list. Connector loads into the first destination
that is available. When it skips the first choice, it says why in chat, for example:
"WorldEdit (server) unavailable: <reason>. Loading into WorldEdit (local) instead."

- **Your own order.** Change it in **Settings → Download destination**. Each server keeps
  its own order, and singleplayer has its own as well. A server or world without its
  own order uses your default order, which you can change in the same section.
- **Flags.** Flags pick a destination for a single download:
  `/schematio download <id|link> [--we|--lm|--file] [--server|--local]`.
  - With a flag, there is no fallback. If that destination can't take the schematic,
    you get the reason.
  - `/schematio quickshareget <code> [password] [flags]` works the same way.
  - The download command also accepts schemat.io schematic links.
- **Detail panel.** The schematic detail panel shows one load button per available
  destination, in your order.
- **When the server fails.** If the server reports that a load failed, and you didn't
  use a flag, Connector falls back to the next destination. If the server doesn't
  confirm in time, Connector does not fall back, because the server may still finish.
  Instead it tells you to check chat or retry with `--local`.

## Servers

- **Fabric servers** running Connector 1.6.0 now announce themselves to 1.6.0 clients.
  They tell the client whether they can load into WorldEdit: that needs WorldEdit, an
  API token, and `download` left enabled. Singleplayer and LAN worlds never announce
  themselves, so routing there stays local. Clients older than 1.6.0 don't see the
  announcement and behave exactly as before.
- **Paper servers** running 1.6.0 advertise server WorldEdit for each player. A player
  only gets it if they have `schematio.download`, the server has a token, and
  `download` is enabled.
  - Older Paper plugins still work as a server destination, through the earlier
    load-clipboard signal.
  - After `/schematio reload` or a token change, Paper and Fabric servers re-announce
    themselves to 1.6.0 clients.
- **Not found.** When a schematic isn't found, both servers now say so plainly:
  "Not found (404). Check the ID. If the schematic is private, its owner must join the
  <community> community on schemat.io for this server to load it."
- **Logs.** Connector's own log lines show only the first characters of a quick-share
  code. The server's command log still records the full command a player typed.

- **Quick-share passwords** that contain spaces now work with `quickshareget` on Paper.

On schemat.io, a community server can now load a member's private schematic when that
member is in the community. An unknown ID now gets the same 404 as a private one, so the
answer no longer reveals whether a private schematic exists.

## Installation

Download the jar for your platform and Minecraft version from
[GitHub Releases](https://github.com/Schem-at/SchematioConnector/releases/tag/v1.6.0)
and replace the 1.5.x jar. Requirements are the same as 1.5.1. To load on the server
from the client, the server and the client both need 1.6.0.

## Validation

- **Unit tests:** core 683 tests, Paper 49 and Fabric 68 (on 26.3 and on 1.21.8). They cover:
  - destination resolution and fallback;
  - flags;
  - per-world profiles;
  - availability reasons;
  - forwarded commands;
  - the handshake rules.
- **Release matrix:** the build plus server start-up smokes for Fabric and Paper on all
  seven Minecraft versions, 1.21.8 to 26.3. The WorldEdit clipboard round-trip passes on
  1.21.8 to 26.3.
  - The 26.x servers stalled on this network while downloading the vanilla jar from
    Mojang.
  - Fabric 26.2 and 26.3 passed on a rerun with a longer timeout.
  - Paper 26.x passed when started from the same Mojang jars that the 1.5.0 validation
    had downloaded.
- **Headless test:** on a Fabric 26.1.2 server with WorldEdit 7.4.3, a protocol client
  showed the following.
  - A hello without the new client flag got no answer, which is how a 1.5.x client sees
    the server.
  - A hello with the flag got HELLO_SERVER with download and server-WorldEdit
    capabilities.
  - An unknown ID got the not-found guidance.
  - `/schematio download 44srfK` followed by `//paste` placed the 65x9x77 schematic
    (45045 blocks).

The routing logic has unit tests, and the server side was tested headlessly. Routing,
the detail panel buttons and the Settings section have **not** been exercised in a real
Minecraft client.
