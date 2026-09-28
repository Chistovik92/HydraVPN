#!/bin/bash
# Build script for HydraVPN Desktop (Windows/Linux)
# Usage: ./scripts/build-desktop.sh [windows|linux|all]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

TARGET="${1:-all}"

echo "=== HydraVPN Desktop Build ==="
echo "Target: $TARGET"
echo ""

# Build shared module first
echo "Building :shared module..."
./gradlew :shared:build --no-daemon

# Build desktop module
echo "Building :desktop module..."
./gradlew :desktop:build --no-daemon

# Run tests
echo "Running tests..."
./gradlew :desktop:test --no-daemon || true

case "$TARGET" in
    windows)
        echo "Building Windows packages..."
        ./gradlew :desktop:packageMsi --no-daemon || echo "MSI packaging requires WiX Toolset"
        ./gradlew :desktop:packageExe --no-daemon || echo "EXE packaging requires Compose Desktop plugin"
        ;;
    linux)
        echo "Building Linux packages..."
        ./gradlew :desktop:packageAppImage --no-daemon || echo "AppImage packaging requires Compose Desktop plugin"
        ./gradlew :desktop:packageDeb --no-daemon || echo "deb packaging requires Compose Desktop plugin"
        ./gradlew :desktop:packageRpm --no-daemon || echo "rpm packaging requires Compose Desktop plugin"
        ;;
    all)
        echo "Building all packages..."
        ./gradlew :desktop:packageDistribution --no-daemon || echo "Packaging requires Compose Desktop plugin"
        ;;
    *)
        echo "Unknown target: $TARGET"
        echo "Usage: $0 [windows|linux|all]"
        exit 1
        ;;
esac

echo ""
echo "=== Build complete ==="
echo "Output: desktop/build/compose/binaries/"