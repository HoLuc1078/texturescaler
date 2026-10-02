#!/usr/bin/env bash
# Compile and run the shared-core self-test (no Minecraft, no Gradle).
# Usage: bash scripts/test-core.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
lib="$root/.tools/lib"
mkdir -p "$lib"
gson="$lib/gson-2.10.1.jar"
if [ ! -f "$gson" ]; then
  echo 'downloading Gson 2.10.1 ...'
  curl -fsSL -o "$gson" 'https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar'
fi
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
find "$root/core/src" -name '*.java' > "$out/sources.txt"
javac -encoding UTF-8 --release 8 -cp "$gson" -d "$out" @"$out/sources.txt"
java -cp "$out:$gson" com.evernight.texturescaler.core.CoreSelfTest