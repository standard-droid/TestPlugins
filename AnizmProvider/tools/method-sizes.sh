#!/usr/bin/env bash
# Lists the largest methods in a built plugin (.cs3), in dex code units.
# Why: ART does not compile a method past its size limit ("Method exceeds compiler instruction
# limit" in logcat; processSource hit it at 17494 before it was split), and such a method then
# runs interpreted on every call. dex2oat's limit for "huge" methods is 10000 code units.
#   AnizmProvider/tools/method-sizes.sh path/to/AnizmProvider.cs3 [limit]
# Needs dexdump (Android SDK build-tools) and unzip. Prints a GitHub warning line per method over
# the limit; exits 0 either way (it is a report, not a gate).
set -euo pipefail
cs3="${1:?usage: method-sizes.sh <plugin.cs3> [limit]}"
limit="${2:-10000}"
dexdump="$(command -v dexdump || true)"
if [ -z "$dexdump" ]; then
  dexdump="$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$dexdump" ] && [ -x "$dexdump" ] || { echo "dexdump not found (set ANDROID_HOME)"; exit 0; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
unzip -qo "$cs3" '*.dex' -d "$tmp"
for dex in "$tmp"/*.dex; do
  "$dexdump" "$dex" | awk '
    /^  Class descriptor/ { cls = $4; gsub(/[\x27;]/, "", cls) }
    /^      name +:/      { name = $3; gsub(/\x27/, "", name) }
    /insns size +:/       { print $4, cls "." name }
  '
done | sort -rn > "$tmp/sizes.txt"
echo "Largest methods (dex code units; ART leaves methods over $limit uncompiled):"
head -15 "$tmp/sizes.txt" | awk '{ printf "  %6d  %s\n", $1, $2 }'
awk -v limit="$limit" '$1 > limit { printf "::warning::%s is %d dex code units (over %d): it will run interpreted. Split it.\n", $2, $1, limit }' "$tmp/sizes.txt"
