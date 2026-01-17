# SchematioConnector

A Minecraft plugin that integrates with [schemat.io](https://schemat.io) to upload, download, browse, and share WorldEdit schematics directly from in-game.

## Features

- **Upload/Download** - Transfer schematics between your clipboard and schemat.io
- **Multi-tier UI** - Choose from Chat, Inventory GUI, or Floating 3D GUI
- **QuickShare** - Create instant temporary share links
- **Progress tracking** - Visual progress bars for transfers
- **Permission-based** - Granular control over who can use what

## Requirements

- **Paper 1.21.4+** (or compatible fork)
- **WorldEdit** (or FastAsyncWorldEdit)
- **Java 21+**

### Optional Dependencies

- **ProtocolLib** - Enhanced authentication features
- **MapEngine** - Schematic preview rendering

## Installation

1. Download the latest release
2. Place `SchematioConnector.jar` in your `plugins/` folder
3. Start the server to generate `config.yml`
4. Configure your community token (see below)
5. Run `/schematio reload` or restart the server

## Configuration

Edit `plugins/SchematioConnector/config.yml`:

```yaml
# API endpoint (default: production)
api-endpoint: "https://schemat.io/api/v1"

# Your community JWT token from schemat.io
# Get from: Community Settings -> Plugin Tokens -> Generate Token
community-token: "your_jwt_token_here"
```

## Commands

### Core Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/schematio info` | Show plugin status | `schematio.list` |
| `/schematio reload` | Reload configuration | `schematio.admin` |
| `/schematio upload` | Upload clipboard to schemat.io | `schematio.upload` |
| `/schematio download <id> [format]` | Download schematic to clipboard | `schematio.download` |

**Download formats:** `schem` (default), `schematic`, `mcedit`

### List Commands (3 UI Tiers)

| Command | UI Type | Permission |
|---------|---------|------------|
| `/schematio list [search] [page]` | Chat (paginated text) | `schematio.list` + `tier.chat` |
| `/schematio list-inv [search]` | Inventory GUI | `schematio.list` + `tier.inventory` |
| `/schematio list-gui [search]` | Floating 3D GUI | `schematio.list` + `tier.floating` |

### QuickShare Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/schematio quickshare` | Create instant share link | `schematio.quickshare` + `tier.chat` |
| `/schematio quickshare-gui` | Share with configuration UI | `schematio.quickshare` + `tier.floating` |
| `/schematio quickshareget <code> [password]` | Download from share link | `schematio.quickshare` |

**Code formats:** Accepts both raw codes (`qs_abc123xy`) and full URLs (`https://schemat.io/share/qs_abc123xy`)

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/schematio setpassword <new>` | Change API password | `schematio.admin` + token scope |
| `/schematio ui [test]` | Debug UI components | `schematio.admin` |

## Permissions

### Base Permissions (What you can do)

| Permission | Default | Description |
|------------|---------|-------------|
| `schematio.upload` | op | Upload schematics to schemat.io |
| `schematio.download` | true | Download schematics from schemat.io |
| `schematio.list` | true | Browse and list schematics |
| `schematio.quickshare` | true | Create and use QuickShare links |
| `schematio.admin` | op | Admin commands (reload, setpassword) |

### UI Tier Permissions (How you can do it)

| Permission | Default | Description |
|------------|---------|-------------|
| `schematio.tier.chat` | true | Use chat-based commands |
| `schematio.tier.inventory` | true | Use inventory GUI commands |
| `schematio.tier.floating` | op | Use floating 3D GUI commands |

### Permission Examples

```yaml
# LuckPerms example: Allow everyone to use chat and inventory, ops get floating GUI
luckperms:
  default:
    permissions:
      - schematio.list
      - schematio.download
      - schematio.quickshare
      - schematio.tier.chat
      - schematio.tier.inventory
  admin:
    permissions:
      - schematio.*
```

## UI Tiers Explained

### Chat Tier (Default)
- Text-based output in chat
- Clickable buttons for actions
- Works everywhere, minimal lag
- Best for quick browsing

### Inventory Tier
- Standard Minecraft inventory GUI
- Item-based navigation
- Familiar interface for players
- Moderate feature set

### Floating Tier (Advanced)
- 3D UI using display entities
- Positioned in front of player
- Interactive buttons and elements
- Auto-closes on walk-away
- Resource intensive - op-only by default

## Troubleshooting

### "Not connected to schemat.io"
1. Check your `community-token` in config.yml
2. Verify the token hasn't expired
3. Check network connectivity
4. Run `/schematio reload`

### Commands not showing
- Ensure WorldEdit is installed for schematic commands
- Check permissions with `/schematio info`
- Verify API connection status

### Floating UI not appearing
- Player needs `schematio.tier.floating` permission
- UI spawns 3 blocks in front of player
- Look straight ahead when running command

## Development

### Building

```bash
./gradlew build
```

Output: `build/libs/SchematioConnector-*.jar`

### Testing

```bash
./gradlew test
```

Unit tests cover layout system, JWT parsing, argument handling, and utilities.

### Documentation

```bash
./gradlew dokkaHtml
```

Output: `build/dokka/html/index.html`

## Links

- [schemat.io](https://schemat.io) - Schematic hosting platform
