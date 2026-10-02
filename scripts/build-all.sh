#!/usr/bin/env bash
# Build every version/loader module and collect the jars into dist/.
# Usage:  bash scripts/build-all.sh [1.20.1 1.21.1 ...]
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
dist="$root/dist"
mkdir -p "$dist"

# Only collect the jars of the current mod version, so leftovers from earlier
# builds in build/libs can never leak an obsolete jar into dist/.
modVersion="$(sed -n 's/^[[:space:]]*mod_version[[:space:]]*=[[:space:]]*//p' "$root/gradle/mod.properties" | tr -d '\r' | head -n1)"

# Every module declares, in its own gradle.properties, the JDK that runs its Gradle
# wrapper (`gradle_jdk`). 21 is the fallback for a module that omits it.
modules=()
while IFS= read -r props; do
  dir="$(dirname "$(dirname "$props")")"
  [ -x "$dir/gradlew" ] || continue
  jdkMajor="$(sed -n 's/^[[:space:]]*gradle_jdk[[:space:]]*=[[:space:]]*//p' "$props" | tr -d '\r' | head -n1)"
  modules+=("${dir#"$root"/}:${jdkMajor:-21}")
done < <(find "$root/versions" -mindepth 3 -maxdepth 3 -name gradle.properties | sort)

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

  jdk="$HOME/.jdks/temurin-$jdkMajor"
  [ -x "$jdk/bin/java" ] || jdk="$(dirname "$(dirname "$(command -v java)")")"

  echo "=== building $rel (JDK $jdkMajor) ==="
  ( cd "$dir" && JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" ./gradlew build --no-daemon --console=plain )
  code=$?
  if [ "$code" -eq 0 ]; then
    find "$dir/build/libs" -maxdepth 1 -name "*-$modVersion.jar" ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
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
