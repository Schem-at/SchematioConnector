# SchematioConnector 1.5.1

Fixes server-side WorldEdit clipboards on Fabric servers running WorldEdit 7.4.

On a Fabric server with WorldEdit 7.4.x, the only WorldEdit line for Minecraft 26.3,
`/schematio download` said the schematic was downloaded to the clipboard, but the
clipboard stayed empty. WorldEdit 7.4 replaced the call Connector used to find a
player's WorldEdit session, and Connector hid that failure. `/schematio upload` and
`/schematio quickshare` had the same problem and reported an empty clipboard.

Connector now finds the session through both the WorldEdit 7.3 and 7.4 APIs. When a
WorldEdit or download step does fail, the player is told that something went wrong
and the cause is written to the server console. An unreachable WorldEdit session is
reported separately from an empty clipboard. Download, upload and quick-share HTTP
failures are logged with their status and response, and a successful download logs
the schematic's size and the player. Validation errors from schemat.io, such as an
unknown schematic ID, now reach the player instead of "Invalid request."

The client mod already handled WorldEdit 7.4 in singleplayer and now shares the same
lookup. The Paper plugin is unchanged.

## Installation

Download the Fabric jar with your Minecraft version in its filename from
[GitHub Releases](https://github.com/Schem-at/SchematioConnector/releases/tag/v1.5.1)
and replace the 1.5.0 jar. Requirements are the same as 1.5.0.

## Validation

- Fabric: 55 tests on 26.3 and 1.21.8, including the WorldEdit 7.3 and 7.4 session
  lookup.
- Headless end-to-end on a Fabric 26.1.2 server with WorldEdit 7.4.3: a mineflayer
  client ran `/schematio download 44srfK`, then `//paste`. With 1.5.0 the download
  reported success and `//paste` answered "Your clipboard is empty". With 1.5.1
  `//paste` placed the 65x9x77 schematic (45045 blocks affected), and an unknown ID
  was reported to the player and logged with its HTTP 422 response.
- Headless server check on Fabric 26.3 with WorldEdit 7.4.6: a Carpet fake player
  ran `/schematio download 44srfK`, and the console logged the 65x9x77 schematic
  loaded into its WorldEdit clipboard. Carpet fake players cannot send WorldEdit
  commands, so `//paste` was checked only on 26.1.
