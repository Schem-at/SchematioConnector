# Modrinth publication

Prepare separate projects for the Fabric mod and Paper plugin, since players
install them in different environments. Use the corresponding README installation
and feature sections as the description. Include the network-use and development
disclosure on both pages.

| Field | Fabric mod | Paper plugin |
| --- | --- | --- |
| Name | Schematio Connector | Schematio Connector |
| Summary | Browse, preview and share Schematio builds in Minecraft. | Connect your server's WorldEdit clipboard to a Schematio community. |
| Client environment | Optional | Unsupported |
| Server environment | Optional | Required |
| Loader | Fabric | Paper |
| License | MIT | MIT |
| Dependencies | Fabric API and Fabric Language Kotlin required; Axiom, Litematica and WorldEdit optional | WorldEdit optional |

The version workflow creates one Fabric version per Minecraft jar and one Paper
version for its tested server list. panel-lib and conditional-mixin are bundled;
players do not install them separately. Litematica requires its own MaLiLib
dependency. Axiom is compile-only and is never included in Connector downloads.

Set the AI-generated code and AI-generated text content disclosures. Connector was
originally handwritten and now uses AI assistance in development. This states the
project history; it does not establish Modrinth eligibility or moderation approval.
Use actual Minecraft captures from the verified release for the gallery. Avoid
AI-generated or AI-edited imagery, including in the icon and description.

[Modrinth's rules](https://modrinth.com/legal/rules) require accurate environment,
license, dependency and disclosure fields. Their
[disclosure guide](https://support.modrinth.com/en/articles/16567675-content-disclosures)
explains the AI code/text and remote-data settings. The description must disclose
Schematio sign-in and data transfers, even though these are the mod's purpose.

Configure `MODRINTH_TOKEN` as a repository secret and `MODRINTH_MOD_PROJECT_ID` and
`MODRINTH_PLUGIN_PROJECT_ID` as repository variables. The owning account must
create and configure those projects first. Dispatch the Release workflow against
the published tag with `publish_modrinth=true`. Missing configuration fails the
publication job instead of silently reporting success. GitHub tag releases and
checksums are independent of Modrinth publication.
