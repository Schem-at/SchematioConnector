# SchematioConnector

A Minecraft plugin that integrates with [schemat.io](https://schemat.io) to let players upload, download, browse, and share WorldEdit schematics directly in-game.

Available for **Paper** (Bukkit) and **Fabric** servers.

## Features

- **Upload** your WorldEdit clipboard to schemat.io
- **Download** schematics by ID directly into your clipboard
- **Browse & search** your community's schematic library
- **Quick Share** — create temporary links to share clipboard contents instantly
- **Dual UI modes** — chat-based or native dialog (1.21.7+ clients)
- **Offline caching** — browse previously fetched schematics when the API is unavailable
- **Permission-based** — granular control over features and UI tiers

## Requirements

| | Paper | Fabric |
|---|---|---|
| Minecraft | 1.21.8+ | 1.21.8+ |
| Java | 21+ | 21+ |
| Server | Paper (or forks) | Fabric + Fabric API |
| WorldEdit | Required (soft-depend) | Required (soft-depend) |

**Optional dependencies (Paper only):** ProtocolLib, MapEngine (schematic preview rendering)

## Installation

1. Download the latest release JAR for your platform from [Releases](https://github.com/schemat-io/SchematioConnector/releases)
2. Place it in your server's `plugins/` (Paper) or `mods/` (Fabric) directory
3. Start the server — a default config file will be generated
4. Set your community token: `/schematio settoken <token>`
   - Get a token from your community settings on [schemat.io](https://schemat.io)

## Configuration

### Paper — `plugins/SchematioConnector/config.yml`

```yaml
# API endpoint
api-endpoint: "https://schemat.io/api/v1"

# Community JWT token (set via /schematio settoken or directly here)
community-token: ""

# Rate limiting (per player)
rate-limit-requests: 10       # max requests per window (0 = disabled)
rate-limit-window-seconds: 60

# Schematic list caching
cache-enabled: true
cache-ttl-seconds: 300        # 5 minutes (min 30, max 3600)

# Default UI mode: chat or dialog
default-ui-mode: chat

# Disable specific commands (they won't register at all)
# Available: upload, download, list, search, quickshare, settings
# Admin commands (reload, settoken, setpassword, info) cannot be disabled.
disabled-commands:
  - list
  - search
```

### Fabric — `config/schematioconnector/config.properties`

```properties
api_endpoint=https://schemat.io/api/v1
api_token=
disabled_commands=list,search
```

## Commands

The base command is `/schematio`. Aliases: `/schem`, `/sch`, `/sio`

Run `/schematio` with no arguments for a list of available commands.
Use `/schematio <command> --help` for detailed per-command usage.

### Player Commands

| Command | Description | Permission |
|---|---|---|
| `/schematio download <id\|code\|url> [format\|password]` | Download a schematic or quick share to clipboard | `schematio.download` |
| `/schematio get ...` | Alias for `download` | `schematio.download` |
| `/schematio upload` | Upload clipboard to schemat.io | `schematio.upload` |
| `/schematio list [search] [page]` | Browse schematics | `schematio.list` |
| `/schematio search <query> [page]` | Search schematics (alias for list with query) | `schematio.list` |
| `/schematio quickshare [options]` | Create a temporary share link from clipboard | `schematio.quickshare` |
| `/schematio settings` | Configure your preferences (UI mode) | `schematio.use` |
| `/schematio info` | Show plugin status and connection info | `schematio.use` |

### Admin Commands (OP only)

| Command | Description | Permission |
|---|---|---|
| `/schematio settoken <token>` | Set the community API token | `schematio.admin` |
| `/schematio setpassword <password>` | Set your API password | `schematio.admin` |
| `/schematio reload` | Reload configuration | `schematio.admin` |

### Flags

**UI mode** — available on all commands that support multiple UI modes:

| Flag | Description |
|---|---|
| `-c`, `--chat` | Force chat mode |
| `-d`, `--dialog` | Force dialog mode |

**Quick share options** — `/schematio quickshare`:

| Flag | Description | Default |
|---|---|---|
| `-e`, `--expires <duration>` | Expiration time (`30m`, `1h`, `24h`, `7d`, `1w`) | `24h` |
| `-l`, `--limit <count>` | Download limit (`0` = unlimited) | `0` |
| `-p`, `--password <pass>` | Password-protect the share | none |

Flags accept both `=` and space-separated syntax: `-e=7d` or `-e 7d`

**List/search filters:**

| Flag | Description |
|---|---|
| `--visibility=<all\|public\|private>` | Filter by visibility |
| `--sort=<created_at\|updated_at\|name\|downloads>` | Sort field |
| `--order=<asc\|desc>` | Sort direction |

### Download Input Detection

The `download` / `get` command automatically detects what you're downloading:

- **Schematic ID** (e.g., `abc123`) — downloads from community library. Second arg is format (`schem`, `schematic`, `mcedit`)
- **Quick share URL** (e.g., `https://schemat.io/share/xyz`) — downloads a quick share. Second arg is password
- **Quick share code** (e.g., `qs_abc123`) — downloads a quick share. Second arg is password

## Permissions

### Feature Permissions (what players can do)

| Permission | Description | Default |
|---|---|---|
| `schematio.use` | Basic access (info, settings) | Everyone |
| `schematio.download` | Download schematics and quick shares | Everyone |
| `schematio.list` | Browse and search schematics | Everyone |
| `schematio.quickshare` | Create quick share links | Everyone |
| `schematio.upload` | Upload schematics | OP only |
| `schematio.admin` | Admin commands (settoken, setpassword, reload) | OP only |

### UI Tier Permissions (how players interact)

| Permission | Description | Default |
|---|---|---|
| `schematio.tier.chat` | Chat-based UI (text + clickable actions) | Everyone |
| `schematio.tier.dialog` | Native dialog UI (requires 1.21.7+ client) | Everyone |

### UI Mode Resolution

The plugin determines which UI mode to use in this order:

1. **Command flag** (`-c` or `-d`) — one-time override
2. **Player preference** — set via `/schematio settings`
3. **Server default** — `default-ui-mode` in config
4. **Fallback** — `chat`

If a player lacks permission for their preferred mode, the plugin falls back to the other mode automatically.

### LuckPerms Example

```yaml
default:
  permissions:
    - schematio.use
    - schematio.list
    - schematio.download
    - schematio.quickshare
    - schematio.tier.chat
    - schematio.tier.dialog
admin:
  permissions:
    - schematio.*
```

## Building from Source

```bash
git clone https://github.com/schemat-io/SchematioConnector.git
cd SchematioConnector
./gradlew build
```

Output JARs:
- **Paper:** `bukkit/build/libs/SchematioConnector-<version>-all.jar`
- **Fabric:** `fabric/build/libs/SchematioConnector-<version>.jar`

### Project Structure

```
SchematioConnector/
├── core/       # Shared Kotlin module (no Minecraft dependencies)
│                 Validation, HTTP client, dialog definitions, caching
├── bukkit/     # Paper/Bukkit plugin
│                 Commands, UI modes, WorldEdit integration
└── fabric/     # Fabric mod
                  Brigadier commands, Fabric WorldEdit integration
```

## Troubleshooting

### "Not connected to schemat.io"
1. Check your token with `/schematio info`
2. Set a token with `/schematio settoken <token>`
3. Verify the token hasn't expired on schemat.io
4. Check server network connectivity
5. Run `/schematio reload`

### Commands not showing up
- Ensure WorldEdit is installed (required for upload/download/list)
- Check the `disabled-commands` list in config
- Verify the player has the correct permission node

### Dialog mode not working
- Requires a 1.21.7+ Minecraft client
- Check that the player has `schematio.tier.dialog` permission
- Falls back to chat mode automatically if unavailable

## Links

- [schemat.io](https://schemat.io) — Schematic hosting platform
- [GitHub](https://github.com/schemat-io/SchematioConnector) — Source code and issues
