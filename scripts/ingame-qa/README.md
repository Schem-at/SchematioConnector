# In-game QA harness

Drives a real Minecraft session end to end:
- a local Paper server running this checkout's plugin (with WorldEdit);
- the Fabric dev client running this checkout's mod, with [MC-Inspector](https://github.com/Nano112/MC-Inspector);
- a local schemat.io at `https://schemati.test`.

It replaces the ad-hoc throwaway worktree, FIFO console and scratch scripts used before.

The schemat.io side lives in the schemati repo. `qa:connector-fixtures` mints the players, the community, the tokens and the rich-text data, and its `docs/qa/connector-in-game.md` points here.

## Requirements

- **schemati:** running locally (Sail, container `schemati-laravel.test-1`), with `https://schemati.test` reachable.
- **MC-Inspector:** a checkout with a built jar (`./gradlew :fabric:<version>:buildAndCollect`). Set `MC_INSPECTOR_ROOT`; the default is `~/Documents/code/MC-Inspector`.
  - `imgui_items` / `imgui_click` need MC-Inspector 0.1.2 or newer.
  - The newest jar for the client version is used. `-Dschematio.inspector.jar=` pins one.
- **JDKs:** 21 and 25 (Gradle toolchains). Paper 1.21.11 runs on 25, so WorldEdit 7.4 loads.
- **Python:** Python 3 (stdlib only).

## Quick start

```sh
Q=scripts/ingame-qa/qa.py
python3 $Q fixtures --rich-text --quickshare-failure   # mint a fresh fixture set (prints names, never tokens)
python3 $Q server start                                 # Paper on 127.0.0.1:25577, RCON on 25578
python3 $Q client start                                 # dev client, signed in as MOD_TOKEN_MEMBER, joined
python3 $Q run scripts/ingame-qa/scenarios/*.json       # scenarios -> report + screenshots
python3 $Q client stop; python3 $Q server stop
python3 $Q fixtures --teardown
```

Other commands:

| Command | What it does |
|---|---|
| `qa.py env` | Lists the key names in the fixture env file. |
| `qa.py signin MOD_TOKEN_DENIED` | Switches the running client to another fixture user. |
| `qa.py server cmd "tp @a 0 80 0"` | Sends a console command over RCON and prints the reply. This replaces the old `tail -f console.in` FIFO. |
| `qa.py click "Run" --window Tools` | Clicks an ImGui item by its label. |
| `qa.py call imgui_items '{"window":"Browse"}'` | Lists what is clickable, or calls any other MC-Inspector tool. |
| `qa.py shot NAME` | Saves a screenshot to `build/ingame-qa/shots/NAME.png`. |

To try another checkout's mod, for example a worktree under review, set `QA_CHECKOUT=/path/to/worktree`. The client is built from that checkout, and output stays in this one.

## Where things go

Everything is written under `build/ingame-qa/`, which is git-ignored:

| Path | Contents |
|---|---|
| `server-<version>/` | The throwaway Paper server: world, plugins, and a `config.yml` holding `PLUGIN_TOKEN` (mode 600). Your `bukkit/run/` dev world is untouched. |
| `server.log`, `client.log` | Gradle output of each process. |
| `rcon.secret` | The random RCON password (mode 600). |
| `runs/<timestamp>-<scenario>/` | `report.md` (one pass/fail row per step) plus the scenario's screenshots. |
| `shots/` | Ad-hoc screenshots. |

The client's game directory is `build/release-readiness/ingame-qa/<version>/`, from `scripts/client-smoke.init.gradle`.

## Fixture env

The fixture env is `~/Documents/code/schemati/storage/app/qa/connector.env` (override with `QA_ENV_FILE`). It is mode 600 and written by `qa:connector-fixtures`.

| Keys | Meaning |
|---|---|
| `SCHEMATIO_BASE_URL` | Base URL of the local schemat.io. |
| `COMMUNITY_SLUG`, `OWNER_NAME` | The fixture community and its owner. |
| `MOD_TOKEN_GRANTED`, `GRANTED_UUID`, `GRANTED_NAME` | A user with `tools.remote-run`. |
| `MOD_TOKEN_MEMBER`, `MEMBER_UUID`, `MEMBER_NAME` | A community member with the Toolsmith role. |
| `MOD_TOKEN_DENIED`, `DENIED_UUID`, `DENIED_NAME` | A user with no permissions. |
| `PLUGIN_TOKEN` | The community token used by the plugin. |
| `RICHTEXT_SCHEMATIC_ID` | Only with `--rich-text`. |
| `MOD_TOKEN_QSFAIL`, `QSFAIL_UUID`, `QSFAIL_NAME` | Only with `--quickshare-failure`. |
| `QA_SUFFIX` | The suffix of this fixture set. |

Player UUIDs are offline-mode UUIDs, so the offline Paper server sees the same UUIDs as schemat.io and plugin permission checks line up.

The harness never prints secret values. Scenarios cannot reference `*TOKEN*`, `*PASSWORD*` or `*SECRET*` keys.

## Scenario format

A scenario is a JSON file with `name`, `description` and a list of `steps`. Steps run in order. A step stops the run when it fails, unless it is a `check` (a checklist item).

Any step can carry `"check": "text"`, which records it as a named checklist item, or `"title": "text"`. String values may use `${KEY}` for non-secret env keys, e.g. `${COMMUNITY_SLUG}`.

| Step | What it does |
|---|---|
| `{"signin": "MOD_TOKEN_MEMBER"}` | Signs the client in as that fixture user and reloads `/mod/me`. |
| `{"close_panels": true}` | Closes every panel-lib panel, so the run starts from a known overlay. |
| `{"open_panel": "schematioconnector:browse", "wait": 40}` | Opens a panel, then waits that many ticks. |
| `{"click": "Run", "window": "Tools", "index": 0, "hover": false}` | Clicks an ImGui item by label. Use `"##id"` to match the ImGui id, which survives text changes. |
| `{"expect_item": "Manage", "window": "Browse", "present": true}` | Passes when the item is drawn (or absent, with `false`). |
| `{"expect_js": "<exec_script code>", "equals": true}` | Compares the script's result. |
| `{"command": "/schematio tool list", "expect_chat": "Tools you can run"}` | Runs a player command and waits for a new chat line. |
| `{"server": "time set day"}` | Sends a console command over RCON. |
| `{"tool": "press_key", "args": {"key": "t"}}` | Calls any MC-Inspector tool. |
| `{"wait": 20}` | Waits that many ticks. |
| `{"shot": "01-name", "hide_hud": false}` | Saves a screenshot into the run folder. |

For ImGui, prefer `click` / `expect_item` over pixel coordinates. They address items by label in the frame ImGui just drew, and they press and release in separate frames after a hover, as a person would.

For world screenshots, the client is flying (`client start` sets it), the server is peaceful with daylight fixed, and sound is muted.

## Notes

- **Sign-in:** `signin` sets the mod's session by reflection. The dev client has no Mojang session to exchange for a token, so this only works in development.
- **Calling ImGui:** never call ImGui natively from `exec_script`; it asserts outside a frame and kills the client. Use `imgui_items` / `imgui_click`.
