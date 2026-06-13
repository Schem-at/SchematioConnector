# SchematioConnector

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.8%20%E2%80%93%201.21.11%2C%2026.1-62b47a)
![Paper](https://img.shields.io/badge/Paper-1.21.x-blue)
![Fabric](https://img.shields.io/badge/Fabric-client%20%2B%20server-dbd0b4)
![License](https://img.shields.io/badge/license-MIT-green)

The in-game companion for **[schemat.io](https://schemat.io)** - browse, upload, share, and manage Minecraft schematics without leaving the game.

SchematioConnector ships as two things:

| Component | What it is | Where it runs |
|---|---|---|
| **Paper plugin** | Server-side `/schematio` commands with chat and native-dialog UIs | Paper 1.21.x servers (and forks) |
| **Fabric mod** | A full client-side UI (browser, upload wizard, thumbnail composer, communities) plus the same server commands when installed on a Fabric server | Fabric 1.21.8 - 1.21.11 and 26.1 |

## Supported versions

| Platform | Versions | Notes |
|---|---|---|
| Paper | 1.21.x | One version-agnostic jar (`api-version: 1.21`) |
| Fabric | **1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1** | One jar per Minecraft version |
| Fabric (1.20.x) | *Planned* | A future backport target, deliberately deferred until the multi-version pipeline is settled. |

26.1 is newly supported: MC 26.x rewrote the GUI framework (`GuiGraphics` is gone) and the block/fluid render pipeline, and the client UI - including the thumbnail renderer - is a fresh port. It builds and is validated, but please report any rendering issues you hit.

Java 21+ is required at runtime on the 1.21.x targets; MC 26.1 itself requires Java 25.

## Features

### Fabric client (the full experience)

- **Schematic browser** - search, tag filtering (including tag *filter values*), thumbnails, detail view, save to disk. Open it with the **K** key (rebindable, *Controls → Misc*) or `/schematio`.
- **Upload wizard** - upload from a local file, your Litematica schematic, or your WorldEdit clipboard, with metadata, tags, and co-authors (with head avatars).
- **Thumbnail composer** - render your schematic to a thumbnail in-game: orbit/pan/zoom camera, isometric or perspective projection, FOV control, angle presets, and transparent / HDRI / studio backgrounds. 16:9 offscreen capture.
- **Rich-text descriptions** - schematic descriptions render formatted, with an inline rich-text editor for your own uploads.
- **Communities** - browse community libraries, manage membership, accept invitations, and moderate (kick, role management) where your community role allows it. Community tags included.
- **Quick shares** - create temporary share links from the UI or commands, and load shares others send you (password-protected shares supported).
- **Tag system** - the global Minecraft tag tree plus per-community tags, with assignability rules and filter definitions, both for browsing and for tagging uploads.
- **Litematica integration** (optional) - download straight into a Litematica placement, export Litematica schematics to schemat.io, browser buttons inside Litematica's GUI, and schematic snapshot thumbnails.
- **WorldEdit integration** (optional) - upload from / download into the WorldEdit clipboard.
- **Player auth** - the client authenticates you with schemat.io via your Mojang session (a player-scoped JWT; no manual token setup needed).
- **Offline behavior** - previously fetched listings stay browsable when the API is unreachable, and the UI degrades gracefully to a limited mode when Litematica/WorldEdit aren't installed.

### Paper plugin / Fabric server

- **Upload** the WorldEdit clipboard to schemat.io, **download** schematics by ID into the clipboard.
- **Browse & search** your community's library from chat or a native dialog (1.21.7+ clients).
- **Quick shares** - create and redeem temporary share links.
- **Dual UI modes** - chat-based or native dialog, per-player preference with permission tiers.
- **Offline caching**, per-player **rate limiting**, and granular **permissions**.

## Installation

### Fabric (client or server)

1. Install [Fabric Loader](https://fabricmc.net/use/).
2. Install the required dependencies into `mods/`:
   - **[Fabric API](https://modrinth.com/mod/fabric-api)**
   - **[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)**
3. *(Optional, client)* Install **[Litematica](https://modrinth.com/mod/litematica)** + **[MaLiLib](https://modrinth.com/mod/malilib)** to enable load-to-placement, export, and snapshot thumbnails, and **WorldEdit** for clipboard upload/download. Without them the mod runs in a limited mode (browsing and file-based upload still work).
4. Download the jar **matching your Minecraft version** - the naming is:

   ```
   SchematioConnector-Fabric-mc<minecraft version>-<mod version>.jar
   e.g. SchematioConnector-Fabric-mc1.21.11-1.1.1.jar  →  for Minecraft 1.21.11
        SchematioConnector-Fabric-mc26.1-1.1.1.jar     →  for Minecraft 26.1
   ```

5. Drop it in `mods/` and start the game. On a client, press **K** or run `/schematio` - you'll be signed in automatically via your Mojang session.

### Paper

1. Download `SchematioConnector-Paper-<version>.jar` from [Releases](https://github.com/schemat-io/SchematioConnector/releases) and drop it into `plugins/`.
2. Start the server once to generate `plugins/SchematioConnector/config.yml`.
3. Set your community token (from *Community Settings → Plugin Tokens* on schemat.io):

   ```
   /schematio settoken <token>
   ```

4. *(Optional)* Install **WorldEdit** to enable clipboard upload/download, and **ProtocolLib** / **MapEngine** for preview rendering.

## Commands

### Fabric client - `/schematio`

| Command | Description |
|---|---|
| `/schematio` | Open the schematic browser (same as the **K** keybind) |
| `/schematio open` / `browse` | Open the schematic browser |
| `/schematio upload` | Upload the current selection / a local file |
| `/schematio download <id>` | Download a schematic into Litematica |
| `/schematio quickshare` | Create a quick share |
| `/schematio quickshareget <code> [password]` | Load a quick share by access code |
| `/schematio help` | Show the command list |

### Paper / Fabric server - `/schematio` (aliases: `/schem`, `/sch`, `/sio`)

| Command | Description | Permission |
|---|---|---|
| `/schematio download <id\|code\|url> [format\|password]` (alias `get`) | Download a schematic or quick share to the clipboard - auto-detects IDs, share codes, and share URLs | `schematio.download` |
| `/schematio upload` | Upload clipboard to schemat.io | `schematio.upload` |
| `/schematio list [search] [page]` | Browse schematics | `schematio.list` |
| `/schematio search <query> [page]` | Search schematics | `schematio.list` |
| `/schematio quickshare [options]` | Create a temporary share link from the clipboard | `schematio.quickshare` |
| `/schematio quickshareget <code> [password]` | Redeem a quick share | `schematio.quickshare` |
| `/schematio settings` | Personal preferences (UI mode) | `schematio.use` |
| `/schematio info` | Plugin status and connection info | `schematio.use` |
| `/password [new_password]` | Set your own schemat.io API password | `schematio.password` |
| `/schematio settoken <token>` | Set the community API token | `schematio.admin` |
| `/schematio setpassword <password>` | Set your API password | `schematio.admin` |
| `/schematio reload` | Reload configuration | `schematio.admin` |

Useful flags (run `/schematio <command> --help` for the full set):

- `-c` / `--chat` and `-d` / `--dialog` - force a UI mode for one command
- Quick share: `-e/--expires <30m|1h|24h|7d|1w>`, `-l/--limit <n>`, `-p/--password <pass>`
- List/search: `--visibility=<all|public|private>`, `--sort=<created_at|updated_at|name|downloads>`, `--order=<asc|desc>`

### Permissions (Paper)

| Permission | Description | Default |
|---|---|---|
| `schematio.use` | Basic access (info, settings) | everyone |
| `schematio.download` | Download schematics and quick shares | everyone |
| `schematio.list` | Browse and search | everyone |
| `schematio.quickshare` | Create and redeem quick shares | everyone |
| `schematio.password` | Set your own API password | everyone |
| `schematio.upload` | Upload schematics | op |
| `schematio.admin` | settoken / setpassword / reload | op |
| `schematio.tier.chat` | Chat-based UI | everyone |
| `schematio.tier.dialog` | Native dialog UI (1.21.7+ clients) | everyone |

UI mode resolution: command flag → player preference (`/schematio settings`) → server default (`default-ui-mode`) → chat.

## Configuration

### Fabric - `config/schematioconnector/config.properties`

| Key | Purpose |
|---|---|
| `api_endpoint` | API base URL (default `https://schemat.io/api/v1`) |
| `client_token` | *Client:* cached player JWT - managed automatically by Mojang-session auth, you normally never touch it |
| `api_token` | *Server:* the community token used for server-side commands (`/schematio settoken` writes it) |
| `disabled_commands` | *Server:* comma-separated subcommands to not register (e.g. `list,search`) |
| `trust_all_certificates` | Trust self-signed certs - local development only, never in production |

### Paper - `plugins/SchematioConnector/config.yml`

| Key | Purpose | Default |
|---|---|---|
| `api-endpoint` | API base URL | `https://schemat.io/api/v1` |
| `community-token` | Community JWT (or use `/schematio settoken`) | empty |
| `rate-limit-requests` / `rate-limit-window-seconds` | Per-player API rate limiting (`0` disables) | `10` / `60` |
| `cache-enabled` / `cache-ttl-seconds` | Listing cache + offline browsing | `true` / `300` |
| `default-ui-mode` | `chat` or `dialog` | `chat` |
| `disabled-commands` | Subcommands to not register at all | `list, search` |
| `trust-all-certificates` | Local development only | `false` |

## Building from source

Requirements: **JDK 25** for the Gradle daemon (`gradle/gradle-daemon-jvm.properties` pins it - Loom requires the Gradle JVM to be at least the newest MC target's Java version, and 26.1 targets Java 25) **and JDK 21** for the 1.21.x compile toolchains (auto-provisioned/detected by Gradle if installed), Git. Gradle comes via the wrapper (9.4.1).

```bash
git clone https://github.com/schemat-io/SchematioConnector.git
cd SchematioConnector

# Paper plugin → bukkit/build/libs/SchematioConnector-Paper-<version>.jar
./gradlew :bukkit:build

# Fabric mod, every supported MC version → build/libs/<mod version>/
./gradlew :fabric:buildAllVersions

# ...or a single version: switch the working tree, then build
./gradlew "Set active project to 1.21.8"
./gradlew :fabric:build
```

### Running a dev client

The Fabric module is multi-version (one `:fabric:<version>` subproject each), so an
unqualified `runClient` would match the task in EVERY version and try to launch all
of them at once (they then collide on the shared run dir, the same port, and DevAuth
login). To run exactly one:

- **IntelliJ:** pick a `Client <version>` run config (tracked in `.run/`, e.g.
  `Client 1.21.11`). Each one runs only `:fabric:<version>:runClient`.
- **Command line, a specific version:**

  ```bash
  export JAVA_HOME=/path/to/jdk-21   # JDK 25 for the 26.1 client
  ./gradlew :fabric:1.21.8:runClient
  ```

- **Command line, the active version:** the `:fabric:runClient` / `:fabric:runServer`
  tasks delegate to ONLY the currently active version (set in
  `fabric/stonecutter.gradle.kts`), so this launches a single client:

  ```bash
  ./gradlew :fabric:runClient
  ```

Switch the active dev version with `./gradlew "Set active project to <version>"`.

### Module layout

```
SchematioConnector/
├── core/      Shared Kotlin API client - validation, HTTP, JSON, dialogs, caching.
│              No Minecraft dependencies.
├── bukkit/    Paper plugin (commands, chat/dialog UIs, WorldEdit integration).
└── fabric/    Fabric mod - client UI + server commands. Official Mojang mappings,
               multi-version via Stonecutter (one subproject per MC version).
```

### Multi-version setup (Stonecutter)

The Fabric module builds one jar per Minecraft version from a single source tree using [Stonecutter](https://stonecutter.kikugie.dev/):

- The version list lives in `settings.gradle.kts` (the `stonecutter { ... }` block).
- Per-version dependency pins (Fabric API, Litematica, MaLiLib, the `fabric.mod.json` MC predicate) live in `fabric/versions/<version>/gradle.properties`.
- The active development version is set in `fabric/stonecutter.gradle.kts`; switch with `./gradlew "Set active project to <version>"`.
- All versions compile against **official Mojang mappings** - MC 26.x ships unobfuscated (Yarn was dropped for it), so mojmap is the forward-compatible path. Version differences are handled with Stonecutter comments and string replacements (see `fabric/stonecutter.gradle.kts`).

### Adding a new Minecraft version

1. Add the version to the `stonecutter` block in `settings.gradle.kts`.
2. Create `fabric/versions/<version>/gradle.properties` with the pins: `deps.fabric_api`, `deps.litematica`, `deps.malilib` (Modrinth version IDs), and `mod.mc_compat`.
3. Add it to `buildableVersions` in `fabric/stonecutter.gradle.kts` once it compiles.
4. Fix any API differences with Stonecutter version comments (`//? if >=<version>`).

**26.1** went through exactly this process and is now a full release target (mapping-less loom via `fabric.loom.disableObfuscation=true`, Java 25 toolchain, client UI ported to the post-`GuiGraphics` framework). **1.20.x** is a planned future target via the same per-version mechanism.

## Releasing

See [RELEASING.md](RELEASING.md) for the release pipeline (GitHub Releases for every artifact, plus the wired-but-inactive Modrinth publishing path).

## Troubleshooting

- **"Not connected to schemat.io" (server):** check `/schematio info`, set a token with `/schematio settoken <token>`, verify it hasn't expired, then `/schematio reload`.
- **Client sign-in fails:** the client authenticates via your Mojang session - make sure you're launched with a valid (non-offline) account.
- **Upload/download commands missing (server):** WorldEdit isn't installed, the command is in `disabled-commands`, or the player lacks the permission node.
- **Litematica buttons/thumbnails missing (client):** install Litematica + MaLiLib for your exact MC version.
- **Dialog mode not working:** requires a 1.21.7+ client and `schematio.tier.dialog`; falls back to chat automatically.

## Links

- [schemat.io](https://schemat.io) - the schematic platform this connects to
- [GitHub](https://github.com/schemat-io/SchematioConnector) - source code and [issues](https://github.com/schemat-io/SchematioConnector/issues)
- Modrinth - *coming soon*
