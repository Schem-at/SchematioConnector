# Schematio Connector

Schematio Connector brings your [schemat.io](https://schemat.io) library into Minecraft. Browse schematics, load a build into Litematica or WorldEdit, and upload your own work from the game.

Use the Fabric mod for the in-game browser and upload tools. Install the Paper plugin to give a server access to a Schematio community. When you use both, the mod can load schematics into your server-side WorldEdit clipboard and start uploads from that clipboard.

## Downloads

The next release candidate is **1.3.3**. These download links become available when that release is published. The [latest published release](https://github.com/Schem-at/SchematioConnector/releases/latest) is available now.

| Download | Minecraft | Java |
| --- | --- | --- |
| [Fabric mod for 1.21.8](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc1.21.8-1.3.3.jar) | 1.21.8 | 21 |
| [Fabric mod for 1.21.9](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc1.21.9-1.3.3.jar) | 1.21.9 | 21 |
| [Fabric mod for 1.21.10](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc1.21.10-1.3.3.jar) | 1.21.10 | 21 |
| [Fabric mod for 1.21.11](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc1.21.11-1.3.3.jar) | 1.21.11 | 21 |
| [Fabric mod for 26.1](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc26.1-1.3.3.jar) | 26.1 series | 25 |
| [Fabric mod for 26.2](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Fabric-mc26.2-1.3.3.jar) | 26.2 series | 25 |
| [Paper plugin](https://github.com/Schem-at/SchematioConnector/releases/download/v1.3.3/SchematioConnector-Paper-1.3.3.jar) | 1.21.8–1.21.11, 26.1.2, 26.2 | 21 for 1.21; 25 for 26.x |

Choose the Fabric jar for your Minecraft version. A single Paper jar covers the server versions listed above. See the [source code and issue tracker on GitHub](https://github.com/Schem-at/SchematioConnector).

## Install the Fabric mod

Install [Fabric Loader](https://fabricmc.net/use/installer/), then put the Connector jar in your instance's `mods` folder with [Fabric API](https://modrinth.com/mod/fabric-api) and [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin). Connector 1.3.3 requires Fabric Language Kotlin 1.13.12 or later.

For Litematica placements, add [Litematica](https://modrinth.com/mod/litematica) and [MaLiLib](https://modrinth.com/mod/malilib) for the same Minecraft version. [WorldEdit](https://modrinth.com/plugin/worldedit) is optional for local clipboard operations. The shared panel-lib interface is included in Connector.

Join a world and press **K** to open the overlay. Select **Schematio → Browse**, or run `/schematio`. Your Minecraft session signs you in to Schematio. Public browsing and saving files are available without Litematica or WorldEdit.

Search for a schematic and open its details. Save the file to disk, or use the Litematica action to create a placement. The upload wizard accepts a local file, a Litematica selection, or a WorldEdit clipboard. Add a name, description, visibility, tags, and co-authors before submitting.

## Install the Paper plugin

Put the Paper jar in your server's `plugins` folder and restart. Install WorldEdit to enable clipboard uploads and downloads.

In your Schematio community settings, create a plugin token. Set it from the server console:

```text
schematio settoken <your-community-token>
```

The token gives the server access to that community. Keep it out of screenshots and public configuration files.

Players can run `/schematio download <id>` to load a schematic into their WorldEdit clipboard. Paste it with `//paste`. To upload, make a WorldEdit selection, run `//copy`, then `/schematio upload`.

The plugin also works with players who have no client mod. Commands use chat or Minecraft's native dialogs. Server owners control access through permissions; `schematio.upload` defaults to operators. The [README](https://github.com/Schem-at/SchematioConnector#commands) lists commands and permissions.

## Use the mod with a Paper server

Install the mod on your client and the plugin with WorldEdit on the server. Configure the plugin's community token and join with the Minecraft account linked to your Schematio profile.

The mod checks the server's community identity against Schematio. Once the connection is verified, a schematic's detail view offers the server clipboard action. Select it to have the server fetch the schematic into your WorldEdit clipboard, then run `//paste`.

To upload from the server, run `//copy` and choose the server clipboard in the mod's upload flow. The plugin sends the clipboard to Schematio as a draft. Complete its details in the mod before publishing it. Loading or uploading a clipboard does not paste blocks into the world.

Server owners can control these actions with `schematio.clipboard.load` and `schematio.clipboard.upload`. Both default to allowed. A failed identity check, missing WorldEdit, or denied permission prevents the corresponding action. The Fabric server mod provides server commands; this plugin-to-mod bridge currently belongs to the Paper plugin.

## Troubleshooting

If Minecraft refuses to load the mod, check the Minecraft version printed in the jar's filename and update Fabric API and Fabric Language Kotlin. For Litematica integration, check that Litematica and MaLiLib also match your Minecraft version.

If the server clipboard option is missing, check that the Paper plugin and WorldEdit are enabled, the community token is configured, and the mod shows a verified server connection. Rejoin after changing the token. Client settings also let you disable server requests to open the Schematio interface.

Report problems in the [GitHub issue tracker](https://github.com/Schem-at/SchematioConnector/issues). Include your Minecraft and Connector versions, whether you use the mod or plugin, and the steps that failed. Remove tokens and account credentials from logs before attaching them.
