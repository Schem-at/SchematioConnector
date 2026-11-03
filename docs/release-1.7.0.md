# SchematioConnector 1.7.0

You can now run schemat.io tools from the game. A tool creates or transforms a build on
schemat.io, and Connector loads the result back into your clipboard. Descriptions of
schematics and communities now show their formatting, and the mod's editor keeps it.

## Tools

To run a tool, your community role needs the "run remote tools" permission on
schemat.io, or your own account needs tool access.

- **In the mod.** Open **Schematio → Tools**, pick a tool and fill in its form. The form
  checks required settings before it sends anything.
  - A schematic input can be your WorldEdit clipboard, a Litematica placement or
    selection, or a file from your schematics folder.
  - If you can run tools for more than one community, choose which one under **Run as**.
  - A schematic result loads like a download: into the server's or your own WorldEdit
    clipboard, or into Litematica, in the order set in Settings. Other results, such as
    images, are saved in a `tools` folder inside your downloads folder.
  - A run keeps going if you close the panel. Reopen **Tools** to see its progress, or
    click **Cancel run**.
- **On a Paper server.** `/schematio tool list` lists the tools your role allows. Run one
  with `/schematio tool <name> key=value ...`. Put quotes around a value with spaces,
  such as `label="north tower"`.
  - Tab completion suggests tool names, their settings and, where there is a fixed
    choice, the values.
  - A tool that takes a schematic uses your WorldEdit clipboard, so `//copy` first.
  - Progress shows above the hotbar. The result replaces your WorldEdit clipboard; run
    `//paste` to place it.
  - `/schematio tool cancel` stops your run. Tools that need an image or another file
    must be run from the mod.
  - The command needs `schematio.tool`, which defaults to everyone.

## Formatted descriptions

Schematic, community and upload descriptions follow schemat.io's formatting rules, so
they look the same in game as on the site: headings, bold, italic, underline, strike,
inline code, the 16 Minecraft colours, bullet and numbered lists, quotes and links.
The description editor has controls for each of them, and saving a description you
didn't change sends nothing.

The "…" character still shows as "?" in the mod. The fix needs a new panel-lib release.

## Fixes

- **Browse → Communities** opens a list under the button. Before 1.7.0 it opened on
  hover beside the whole panel, and clicking its label closed it again.
- The Communities picker no longer stays disabled after a failed startup request.
  Connector asks schemat.io for your account again, at most every 30 seconds.
- `quickshareget` in chat mode announced the share twice and used two of your
  rate-limit slots. It now does both once.
- A tool you aren't allowed to run is reported as a permission problem instead of
  "Unknown tool".

## Installation

Download the jar for your platform and Minecraft version from
[GitHub Releases](https://github.com/Schem-at/SchematioConnector/releases/tag/v1.7.0)
and replace the 1.6.x jar. Requirements are the same as 1.6.0.

## Validation

- **Unit tests:** core 778 tests, Paper 50, and Fabric 76 on each of the seven Minecraft
  versions. They cover:
  - the tool catalogue, forms and their validation;
  - run polling, cancel and error mapping;
  - the formatting parser and serialiser, against fixtures shared with schemat.io;
  - the 30-second account refresh.
- **Release build:** the build and `verify-release.py` pass for all eight jars on
  1.21.8 to 26.3.
- **In game:** a 1.21.11 client and a local Paper server against a local schemat.io,
  driven by the `scripts/ingame-qa` harness.
  - Browse → Communities passed 14 of 14 steps: the list opens under the button,
    picking the community scopes Browse, and Manage opens the community panel.
  - `/schematio tool list` on Paper listed the member's 8 tools from their community
    role.
  - A formatted description rendered, and editing and saving it kept its formatting.

The other six Minecraft versions passed unit tests and the release server checks, not an
in-game session.
