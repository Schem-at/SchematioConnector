SchematioConnector v1.3.3

Minecraft 26.2 now supports schematic previews and thumbnail capture, including
perspective and transparent backgrounds. The parser bundle adds Windows x64,
Linux x64/arm64, and macOS x64 support alongside macOS arm64.
File previews now create default block entities so chests and similar blocks appear.
Custom block-entity NBT, such as sign text, is not available through the file reader.
The composer keeps the FOV control visible in narrow panels, and its Top preset
preserves the selected projection.

Fixed client shutdown crashes in the bundled panel-lib interface. Updated the
Litematica/MaLiLib dependency pairs for development clients on 1.21.11 and 26.1.
Fabric Language Kotlin 1.13.12 or newer is required.

The Paper bridge ignores duplicate client greetings and rejects attestation
responses from a previous connection. Its attestation cache is bounded and scoped
to the community token, platform, and client nonce.

Downloads include one Paper jar, six Fabric jars, and SHA256SUMS. Choose the Fabric
jar that names your Minecraft version. Install Fabric API and Fabric Language
Kotlin alongside it. Add Litematica/MaLiLib for placements, or WorldEdit for clipboard
operations. The Paper plugin needs a Schematio community token; WorldEdit enables
its clipboard operations.
