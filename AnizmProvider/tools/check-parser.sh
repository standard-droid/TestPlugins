#!/usr/bin/env bash
# Compiles AnizmParser.kt with a standalone kotlinc and checks it against saved anizm.net pages.
# Needs only a JDK and network access to github.com + repo1.maven.org; no Gradle and no Android
# SDK, so it runs where the plugin itself can't be built.
#   AnizmProvider/tools/check-parser.sh [fixtures-dir]
# The saved pages are not in the repo (tools/fixtures is gitignored). Capture them with the
# browser-console script in tools/ParserCheck.kt's header, split into <name>.gz files, and pass
# the folder (default: tools/fixtures).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cache="${ANIZM_TOOLS_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/anizm-parser-check}"
kotlin_version=2.2.20
jsoup_version=1.22.2   # keep in step with the jsoup in build.gradle.kts
json_version=20240303
fixtures="$(cd "${1:-$here/fixtures}" 2>/dev/null && pwd)" || { echo "no fixtures folder: ${1:-$here/fixtures}"; exit 2; }
ls "$fixtures"/*.gz >/dev/null 2>&1 || { echo "no saved pages (*.gz) in $fixtures"; exit 2; }
mkdir -p "$cache"
fetch() { [ -s "$cache/$2" ] || curl -sSfL -o "$cache/$2" "$1"; }
fetch "https://github.com/JetBrains/kotlin/releases/download/v$kotlin_version/kotlin-compiler-$kotlin_version.zip" kotlinc.zip
[ -x "$cache/kotlinc/bin/kotlinc" ] || (cd "$cache" && unzip -qo kotlinc.zip)
fetch "https://repo1.maven.org/maven2/org/jsoup/jsoup/$jsoup_version/jsoup-$jsoup_version.jar" jsoup.jar
fetch "https://repo1.maven.org/maven2/org/json/json/$json_version/json-$json_version.jar" json.jar
cp="$cache/jsoup.jar:$cache/json.jar"
out="$cache/out"; rm -rf "$out"; mkdir -p "$out"
"$cache/kotlinc/bin/kotlinc" -nowarn -cp "$cp" -d "$out" \
  "$here/../src/main/kotlin/com/a/anizm/AnizmParser.kt" "$here/ParserCheck.kt" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f "$out/ParserCheckKt.class" ] || { echo "compile failed"; exit 1; }
exec java -Dfixtures="$fixtures" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$out:$cp:$cache/kotlinc/lib/kotlin-stdlib.jar" ParserCheckKt
