# Command Review Document

This document lists all commands in SchematioConnector for manual review.

## Command Summary

| Command | Tier | Permission | Dependencies | Status |
|---------|------|------------|--------------|--------|
| info | Chat | schematio.list | None | Ready for review |
| reload | Chat | schematio.admin | None | Ready for review |
| upload | Chat | schematio.upload | WorldEdit | Ready for review |
| download | Chat | schematio.download | WorldEdit | Ready for review |
| list | Chat | schematio.list + tier.chat | WorldEdit | Ready for review |
| list-inv | Inventory | schematio.list + tier.inventory | WorldEdit | Ready for review |
| list-gui | Floating | schematio.list + tier.floating | WorldEdit | Ready for review |
| quickshare | Chat | schematio.quickshare + tier.chat | WorldEdit | Ready for review |
| quickshare-gui | Floating | schematio.quickshare + tier.floating | WorldEdit | Ready for review |
| quickshareget | Chat | schematio.quickshare | WorldEdit | Ready for review |
| setpassword | Chat | schematio.admin + token scope | None | Ready for review |
| ui | Chat | schematio.admin | None | Debug only |

---

## Detailed Review

### 1. info
**File:** [InfoSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/InfoSubcommand.kt)

**Purpose:** Shows plugin status and connection information

**Permission:** `schematio.list`

**Review Notes:**
- [ ] Displays correct version
- [ ] Shows API connection status accurately
- [ ] Lists available commands based on permissions
- [ ] Helpful messages when not connected

---

### 2. reload
**File:** [ReloadSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/ReloadSubcommand.kt)

**Purpose:** Reloads plugin configuration from disk

**Permission:** `schematio.admin`

**Review Notes:**
- [ ] Successfully reloads config.yml
- [ ] Re-tests API connection
- [ ] Refreshes commands based on new config
- [ ] Clear feedback on success/failure

---

### 3. upload
**File:** [UploadSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/UploadSubcommand.kt)

**Purpose:** Uploads WorldEdit clipboard to schemat.io

**Permission:** `schematio.upload`

**Dependencies:** WorldEdit

**Review Notes:**
- [ ] Validates clipboard exists before upload
- [ ] Provides clickable link on success
- [ ] Handles API errors gracefully
- [ ] Proper error messages for missing clipboard

---

### 4. download
**File:** [DownloadSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/DownloadSubcommand.kt)

**Purpose:** Downloads schematic by ID to clipboard

**Permission:** `schematio.download`

**Dependencies:** WorldEdit

**Usage:** `/schematio download <id> [format]`

**Formats:** schem, schematic, mcedit

**Review Notes:**
- [ ] Validates schematic ID argument
- [ ] Progress bar shows download progress
- [ ] Handles format conversion correctly
- [ ] Error messages for invalid IDs
- [ ] Debug file write removed (schematic_debug.schem)

---

### 5. list (Chat tier)
**File:** [ListSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/ListSubcommand.kt)

**Purpose:** Browse schematics in paginated chat output

**Permission:** `schematio.list` + `schematio.tier.chat`

**Usage:** `/schematio list [search] [page]`

**Review Notes:**
- [ ] Pagination works correctly
- [ ] Search filters results
- [ ] Clickable [DL] button downloads schematic
- [ ] Hover shows schematic details
- [ ] Suggests alternative UIs if available

---

### 6. list-inv (Inventory tier)
**File:** [ListInvSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/ListInvSubcommand.kt)

**Purpose:** Browse schematics in inventory GUI

**Permission:** `schematio.list` + `schematio.tier.inventory`

**Review Notes:**
- [ ] Inventory opens correctly
- [ ] Pagination works (prev/next buttons)
- [ ] Search via anvil input
- [ ] Clicking schematic shows details
- [ ] Download and preview buttons work

---

### 7. list-gui (Floating tier)
**File:** [ListGuiSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/ListGuiSubcommand.kt)

**Purpose:** Browse schematics in 3D floating UI

**Permission:** `schematio.list` + `schematio.tier.floating`

**Review Notes:**
- [ ] UI spawns at correct position (3 blocks in front)
- [ ] UI dismisses when looking away
- [ ] Interactive elements respond to click
- [ ] Search and pagination work
- [ ] No display entity leaks on disconnect

---

### 8. quickshare (Chat tier)
**File:** [QuickShareSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/QuickShareSubcommand.kt)

**Purpose:** Create instant share link (no configuration)

**Permission:** `schematio.quickshare` + `schematio.tier.chat`

**Review Notes:**
- [ ] Creates share link immediately
- [ ] Uses default expiration settings
- [ ] Validates clipboard exists
- [ ] Returns clickable share link
- [ ] Suggests quickshare-gui for more options

---

### 9. quickshare-gui (Floating tier)
**File:** [QuickShareGuiSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/QuickShareGuiSubcommand.kt)

**Purpose:** Create share link with configuration UI

**Permission:** `schematio.quickshare` + `schematio.tier.floating`

**Review Notes:**
- [ ] UI opens for configuration
- [ ] Expiration can be configured
- [ ] Password protection option works
- [ ] Create button generates link
- [ ] UI dismisses properly

---

### 10. quickshareget
**File:** [QuickShareGetSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/QuickShareGetSubcommand.kt)

**Purpose:** Download from share link

**Permission:** `schematio.quickshare`

**Usage:** `/schematio quickshareget <code|url> [password]`

**Review Notes:**
- [ ] Accepts both code and full URL
- [ ] Extracts code from various URL formats
- [ ] Password prompt when required
- [ ] Clear error for expired/invalid shares
- [ ] Loads schematic to clipboard

---

### 11. setpassword
**File:** [SetPasswordSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/SetPasswordSubcommand.kt)

**Purpose:** Change API password

**Permission:** `schematio.admin` + token `canManagePasswords` claim

**Review Notes:**
- [ ] Only available when token has permission
- [ ] Validates password requirements
- [ ] Secure password handling
- [ ] Clear success/error feedback

---

### 12. ui (Debug)
**File:** [UITestSubcommand.kt](src/main/kotlin/io/schemat/schematioConnector/commands/UITestSubcommand.kt)

**Purpose:** Debug/test UI components

**Permission:** `schematio.admin`

**Review Notes:**
- [ ] Consider removing or hiding in production
- [ ] Or rename to indicate debug-only nature

---

## Permission Summary

### Base Permissions (plugin.yml)
```yaml
schematio.upload: default op
schematio.download: default true
schematio.list: default true
schematio.quickshare: default true
schematio.admin: default op
```

### Tier Permissions (plugin.yml)
```yaml
schematio.tier.chat: default true
schematio.tier.inventory: default true
schematio.tier.floating: default op
```

---

## Files Changed Summary

### New Files
- `ListInvSubcommand.kt` - Renamed from old ListSubcommand
- `ListSubcommand.kt` - New chat-only version
- `QuickShareGuiSubcommand.kt` - New floating UI version
- Test files in `src/test/kotlin/`

### Modified Files
- `plugin.yml` - Added tier permissions and quickshare permission
- `build.gradle.kts` - Added test dependencies and Dokka
- `SchematioConnector.kt` - Updated command registration
- `ListGuiSubcommand.kt` - Added tier permission check, renamed to list-gui
- `QuickShareSubcommand.kt` - Simplified to chat-only instant share
- All command files - Added KDoc documentation
- Utility files - Added KDoc documentation

---

## Testing

Run all tests:
```bash
./gradlew test
```

Current test count: 62 tests
- Layout system tests: ~30 tests
- JWT/Permission parsing tests: ~15 tests
- Argument parsing tests: ~17 tests

---

## Verification Checklist

Before release:
- [ ] All tests pass (`./gradlew test`)
- [ ] Build succeeds (`./gradlew build`)
- [ ] Deploy to test server
- [ ] Test each command manually
- [ ] Verify permissions work as expected
- [ ] Check no memory leaks (display entities cleanup)
- [ ] Review log output for errors
