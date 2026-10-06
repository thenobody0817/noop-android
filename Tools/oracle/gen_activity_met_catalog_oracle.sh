#!/usr/bin/env bash
# Regenerates the ActivityMETCatalog parity oracle from the real Swift source.
#
# The Swift engine is compiled STANDALONE (it imports Foundation and nothing else), driven over the
# grids in the harness, and its own answers are written out as the fixture that both platform suites
# read. The fixture is committed once, in the Android test tree; the Swift suite reaches it by walking
# up from #filePath, the same way Spo2DisplayParityOracleTests does.
#
# Requires a Swift toolchain only when the engine changes - NOT at test time and NOT in CI.
#
# Usage:  Tools/oracle/gen_activity_met_catalog_oracle.sh
#
# Requires docker (the pinned swift:6.0 image) or a local swiftc on PATH.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENGINE_REL="Packages/StrandAnalytics/Sources/StrandAnalytics/ActivityMETCatalog.swift"
HARNESS_REL="Tools/oracle/gen_activity_met_catalog_oracle.swift"
ENGINE="$REPO_ROOT/$ENGINE_REL"
HARNESS="$REPO_ROOT/$HARNESS_REL"

[ -f "$ENGINE" ] || { echo "missing engine: $ENGINE_REL" >&2; exit 1; }

ANDROID_TEST_RES="$REPO_ROOT/android/app/src/test/resources"
mkdir -p "$ANDROID_TEST_RES"

SOURCE_COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$ENGINE" "$WORK/ActivityMETCatalog.swift"
cp "$HARNESS" "$WORK/main.swift"

# The toolchain string is resolved here rather than by shell substitution inside the Swift literal,
# because the fixture must record the version of the build that actually produced it.
if command -v swift >/dev/null 2>&1; then
  TOOLCHAIN="$(swift --version 2>/dev/null | head -1)"
else
  TOOLCHAIN="6.0 (docker image swift:6.0)"
fi
TOOLCHAIN_ESCAPED="${TOOLCHAIN//\\/\\\\}"
TOOLCHAIN_ESCAPED="${TOOLCHAIN_ESCAPED//\"/\\\"}"

cat > "$WORK/shim.swift" <<SHIM
import Foundation
let swiftVersionString = "$TOOLCHAIN_ESCAPED"
let sourceCommit = "$SOURCE_COMMIT"
SHIM

OUT="$WORK/activity_met_catalog_oracle.json"

if command -v swiftc >/dev/null 2>&1; then
  echo "building with local swiftc" >&2
  swiftc -O "$WORK/ActivityMETCatalog.swift" "$WORK/shim.swift" "$WORK/main.swift" -o "$WORK/gen"
  "$WORK/gen" > "$OUT"
else
  echo "building with docker swift:6.0" >&2
  docker run --rm -v "$WORK:/w" -w /w swift:6.0 \
    sh -c 'swiftc -O ActivityMETCatalog.swift shim.swift main.swift -o gen && ./gen' > "$OUT"
fi

cp "$OUT" "$ANDROID_TEST_RES/activity_met_catalog_oracle.json"
echo "wrote:" >&2
echo "  android/app/src/test/resources/activity_met_catalog_oracle.json" >&2