#!/bin/bash
# BTC Studio unified repo: publish the BTC Velocity API straight into repo/
# Usage: bash repo/publish.sh
#
# On Windows use PowerShell instead:
#   .\gradlew.bat :velocity-api:publishMavenPublicationToBtcRepoRepository

set -e
cd "$(dirname "$0")/.."

echo "=== Publishing dev.btc.velocity:api into repo/ ==="
./gradlew :velocity-api:publishMavenPublicationToBtcRepoRepository --console=plain

echo ""
echo "=== Published files ==="
find repo/dev/btc/velocity -name "*.jar" -o -name "*.pom" -o -name "*.module" | sort

echo ""
echo "Next steps:"
echo "  1. (optional) refresh javadoc: unzip repo/dev/btc/velocity/api/*/*-javadoc.jar -> repo/javadoc/velocity/"
echo "  2. commit repo/ and upload it as-is to borntocraftstudio.net/public/repo/"
echo ""
echo "Note: the BTC-CORE API (dev.btc.core:api) is provided from BTC-CORE-Fork and"
echo "      copied into repo/dev/btc/core/ + repo/javadoc/core/ separately."
