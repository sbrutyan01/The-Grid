#!/usr/bin/env bash
# The Grid: shows a Buildroot build as EVIA Paradigma-style blocks.
#   ./run.sh [folder]   folder = a Buildroot workspace (with output/) or an output folder
# Compiles on first run. Uses FlatLaf from the EVIA Paradigma jar when it is installed.
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -d classes ] || [ -n "$(find src -newer classes -name '*.java' 2>/dev/null)" ]; then
  rm -rf classes && javac -d classes src/brblocks/*.java && touch classes
fi
CP=classes
EVIA_JAR=$(ls -1 "$HOME"/.evia-paradigma/packages/app/evia-paradigma-*.jar 2>/dev/null | sort -V | tail -1 || true)
[ -n "$EVIA_JAR" ] && CP="$CP:$EVIA_JAR"
exec java -cp "$CP" brblocks.BuildrootBlocks "$@"
