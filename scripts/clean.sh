#!/usr/bin/env bash
# Remove every generated build artefact and cache (all of it is regenerable).
# The built mod jars in dist/ are KEPT unless --include-dist is passed.
# Usage: bash scripts/clean.sh [--include-dist]
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
find "$root" -type d \( -name build -o -name .gradle -o -name run -o -name run-data -o -name mcmodsrepo \) \
  -not -path '*/.git/*' -prune -print -exec rm -rf {} + 2>/dev/null
find "$root" -type f -name '*.log' -not -path '*/.git/*' -delete 2>/dev/null
if [ "${1:-}" = "--include-dist" ] && [ -d "$root/dist" ]; then rm -rf "$root/dist"; echo 'removed dist/'; fi
echo 'clean done'