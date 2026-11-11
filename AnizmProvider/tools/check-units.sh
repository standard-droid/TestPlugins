#!/usr/bin/env bash
# Compiles Hls.kt, AnizmParser.kt, AnizmJson.kt, AnizmUrls.kt and AnizmCasting.kt with a standalone kotlinc and runs tools/UnitCheck.kt over them:
# playlist parsing, the timestamp-join search, MPEG-TS timestamps, size arithmetic, source credits, address helpers.
# Needs only a JDK and network access to github.com + repo1.maven.org (first run); no Gradle, no
# Android SDK and no saved pages, so it runs anywhere, CI included.
#   AnizmProvider/tools/check-units.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cache="${ANIZM_TOOLS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/anizm-unit-check}"
kotlin_version=2.3.20   # the project's Kotlin (root build.gradle.kts)
jsoup_version=1.22.2   # keep in step with the jsoup in build.gradle.kts
serialization_version=1.10.0   # keep in step with kotlinx-serialization-json in build.gradle.kts
coroutines_version=1.10.2
mkdir -p "$cache"
fetch() { [ -s "$cache/$2" ] || curl -sSfL -o "$cache/$2" "$1"; }
fetch "https://github.com/JetBrains/kotlin/releases/download/v$kotlin_version/kotlin-compiler-$kotlin_version.zip" kotlinc.zip
[ -x "$cache/kotlinc/bin/kotlinc" ] || (cd "$cache" && unzip -qo kotlinc.zip)
fetch "https://repo1.maven.org/maven2/org/jsoup/jsoup/$jsoup_version/jsoup-$jsoup_version.jar" jsoup.jar
for m in core json; do fetch "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-$m-jvm/$serialization_version/kotlinx-serialization-$m-jvm-$serialization_version.jar" "serialization-$m.jar"; done
fetch "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$coroutines_version/kotlinx-coroutines-core-jvm-$coroutines_version.jar" coroutines.jar
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="$cache/jsoup.jar$sep$cache/serialization-core.jar$sep$cache/serialization-json.jar$sep$cache/coroutines.jar"
out="$cache/out-units"; rm -rf "$out"; mkdir -p "$out"
src="$here/../src/main/kotlin/com/a/anizm"
# The serialization compiler plugin ships with kotlinc; AppUtilsShim.kt stands in for CloudStream's AppUtils.
"$cache/kotlinc/bin/kotlinc" -nowarn -Xplugin="$cache/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar" -cp "$cp" -d "$out" \
  "$src/AnizmParser.kt" "$src/AnizmJson.kt" "$src/Hls.kt" "$src/AnizmUrls.kt" "$src/AnizmCasting.kt" "$here/AppUtilsShim.kt" "$here/UnitCheck.kt" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f "$out/UnitCheckKt.class" ] || { echo "compile failed"; exit 1; }
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$out$sep$cp$sep$cache/kotlinc/lib/kotlin-stdlib.jar" UnitCheckKt
