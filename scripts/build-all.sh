#!/usr/bin/env bash
# Build every version/loader module and collect the jars into dist/.
# Usage:  bash scripts/build-all.sh [1.20.1 1.21.1 ...]
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$root/dist"
mkdir -p "$dist"

modules=(
  "versions/1.12.2/forge:17"
  "versions/1.16.5/forge:17"
  "versions/1.16.5/fabric:21"
  "versions/1.18.2/forge:17"
  "versions/1.18.2/fabric:21"
  "versions/1.19.2/forge:17"
  "versions/1.19.2/fabric:21"
  "versions/1.20.1/forge:17"
  "versions/1.20.1/fabric:21"
  "versions/1.21.1/forge:17"
  "versions/1.21.1/fabric:21"
  "versions/1.21.1/neoforge:21"
  "versions/1.21.4/fabric:21"
  "versions/1.21.4/neoforge:21"
)

only=("$@")
fail=0
for entry in "${modules[@]}"; do
  rel="${entry%%:*}"
  jdkMajor="${entry##*:}"
  if [ "${#only[@]}" -gt 0 ]; then
    match=0
    for o in "${only[@]}"; do [[ "$rel" == *"/$o/"* ]] && match=1; done
    [ "$match" -eq 1 ] || continue
  fi
  dir="$root/$rel"
  [ -d "$dir" ] || { echo "skip (missing): $rel"; continue; }

  jdk="$HOME/.jdks/temurin-$jdkMajor"
  [ -x "$jdk/bin/java" ] || jdk="$(dirname "$(dirname "$(command -v java)")")"

  echo "=== building $rel (JDK $jdkMajor) ==="
  ( cd "$dir" && JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" ./gradlew build --no-daemon --console=plain )
  code=$?
  if [ "$code" -eq 0 ]; then
    find "$dir/build/libs" -maxdepth 1 -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
      -exec cp {} "$dist/" \; 2>/dev/null
  else
    echo "FAILED: $rel (exit $code)"
    fail=1
  fi
done
echo
echo "=== dist ==="
ls -1 "$dist" || true
exit "$fail"
