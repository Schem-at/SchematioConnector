#!/bin/bash

# Build SchematioConnector for all supported Minecraft versions
# Outputs to build/release/

set -e

RELEASE_DIR="build/release"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# Version configurations (based on Fabric API releases from Modrinth)
# Format: MC_VERSION|FABRIC_API|YARN_MAPPINGS|LOADER_VERSION
VERSIONS=(
    "1.21.8|0.136.0+1.21.8|1.21.8+build.1|0.17.2"
    "1.21.9|0.134.0+1.21.9|1.21.9+build.1|0.17.2"
    "1.21.10|0.138.0+1.21.10|1.21.10+build.2|0.17.2"
    "1.21.11|0.139.4+1.21.11|1.21.11+build.1|0.17.2"
)

echo "========================================"
echo "Building SchematioConnector for all versions"
echo "========================================"

# Build Bukkit once (it's version-agnostic for Paper 1.21+)
echo ""
echo "Building Bukkit plugin..."
./gradlew :bukkit:shadowJar --quiet
cp bukkit/build/libs/bukkit-*.jar "$RELEASE_DIR/SchematioConnector-Bukkit-1.0.1.jar"
echo "✓ Bukkit build complete"

# Build Fabric for each version
for config in "${VERSIONS[@]}"; do
    IFS='|' read -r MC_VERSION FABRIC_API YARN_MAPPINGS LOADER_VERSION <<< "$config"

    echo ""
    echo "Building Fabric mod for Minecraft $MC_VERSION..."
    echo "  Fabric API: $FABRIC_API"
    echo "  Yarn: $YARN_MAPPINGS"

    # Clear loom cache for this version to avoid conflicts
    rm -rf .gradle/loom-cache 2>/dev/null || true

    ./gradlew :fabric:remapJar \
        -Pfabric_minecraft_version="$MC_VERSION" \
        -Pfabric_api_version="$FABRIC_API" \
        -Pfabric_yarn_mappings="$YARN_MAPPINGS" \
        -Pfabric_loader_version="$LOADER_VERSION" \
        --quiet 2>&1 || {
            echo "✗ Failed to build for $MC_VERSION"
            continue
        }

    # Copy the built JAR
    if ls fabric/build/libs/SchematioConnector-Fabric-mc${MC_VERSION}-*.jar 1> /dev/null 2>&1; then
        cp fabric/build/libs/SchematioConnector-Fabric-mc${MC_VERSION}-*.jar "$RELEASE_DIR/" 2>/dev/null || true
        # Remove sources jar if copied
        rm -f "$RELEASE_DIR"/*-sources.jar
        echo "✓ Minecraft $MC_VERSION build complete"
    else
        echo "✗ No JAR found for $MC_VERSION"
    fi
done

echo ""
echo "========================================"
echo "Build complete! Artifacts in $RELEASE_DIR:"
echo "========================================"
ls -la "$RELEASE_DIR"
