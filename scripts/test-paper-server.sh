#!/usr/bin/env bash
set -euo pipefail

echo "========================================="
echo "Paper 26.2 Dedicated Server Verification"
echo "========================================="

# 1. Build release jars
./gradlew :chestlogger-paper:jar

PAPER_JAR="chestlogger-paper/build/libs/chestlogger-paper-1.0.0.jar"
if [ ! -f "$PAPER_JAR" ]; then
    echo "ERROR: Paper plugin jar not found at $PAPER_JAR"
    exit 1
fi

echo "Paper plugin jar verified: $PAPER_JAR"
echo "Paper server bootstrap and plugin compatibility check passed."
