#!/bin/bash
# Version bump script for SchematioConnector
# Usage: ./scripts/bump-version.sh [major|minor|patch]

set -e

PROPS_FILE="gradle.properties"

if [ ! -f "$PROPS_FILE" ]; then
    echo "Error: $PROPS_FILE not found"
    exit 1
fi

# Read current version
MAJOR=$(grep "versionMajor=" "$PROPS_FILE" | cut -d= -f2)
MINOR=$(grep "versionMinor=" "$PROPS_FILE" | cut -d= -f2)
PATCH=$(grep "versionPatch=" "$PROPS_FILE" | cut -d= -f2)

echo "Current version: $MAJOR.$MINOR.$PATCH"

case "$1" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch)
        PATCH=$((PATCH + 1))
        ;;
    *)
        echo "Usage: $0 [major|minor|patch]"
        echo "  major - Breaking changes (1.0.0 -> 2.0.0)"
        echo "  minor - New features (1.0.0 -> 1.1.0)"
        echo "  patch - Bug fixes (1.0.0 -> 1.0.1)"
        exit 1
        ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH"
echo "New version: $NEW_VERSION"

# Update gradle.properties
cat > "$PROPS_FILE" << EOF
# Project version - follows Semantic Versioning (https://semver.org)
# Bump MAJOR for breaking changes, MINOR for new features, PATCH for bug fixes
versionMajor=$MAJOR
versionMinor=$MINOR
versionPatch=$PATCH
EOF

echo "Updated $PROPS_FILE"
echo ""
echo "Next steps:"
echo "  1. git add gradle.properties"
echo "  2. git commit -m \"Bump version to $NEW_VERSION\""
echo "  3. git tag v$NEW_VERSION"
echo "  4. git push && git push --tags"
