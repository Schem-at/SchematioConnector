# SchematioConnector

SchematioConnector is a Bukkit/Paper plugin that allows players to easily upload and download WorldEdit schematics to and from a remote server. It provides in-game commands for seamless schematic management.

## Features

- Upload WorldEdit schematics directly from in-game
- Download schematics to your WorldEdit clipboard
- Progress bars for upload and download operations
- Integration with Paper's Adventure API for rich text messages


## Configuration

After first run, a `config.yml` file will be created in the `plugins/SchematioConnector` folder. Edit this file to set your API key and endpoint:

```yaml
api-key: "your_api_key_here"
api-endpoint: "https://your-api-endpoint.com"
```

## Usage

### Commands

- `/schematio upload` - Uploads the schematic in your current WorldEdit clipboard
- `/schematio download <schematic-id>` - Downloads a schematic and loads it into your WorldEdit clipboard


## Building from Source

1. Clone the repository:
   ```
   git clone https://github.com/schem-at/SchematioConnector.git
   ```
2. Navigate to the project directory:
   ```
   cd SchematioConnector
   ```
3. Build the project using Gradle:
   ```
   ./gradlew build
   ```
4. The built JAR file will be in the `build/libs` directory.
