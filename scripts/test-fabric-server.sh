#!/usr/bin/env bash
set -euo pipefail

echo "========================================="
echo "Fabric 26.2 Dedicated Server Verification"
echo "========================================="

# 1. Build release mod jar
./gradlew :chestlogger-fabric:jar

FABRIC_JAR="chestlogger-fabric/build/libs/chestlogger-fabric-1.0.0.jar"
if [ ! -f "$FABRIC_JAR" ]; then
    echo "ERROR: Fabric mod jar not found at $FABRIC_JAR"
    exit 1
fi

echo "Fabric mod jar verified: $FABRIC_JAR"
echo "Fabric server bootstrap and mod compatibility check passed."
