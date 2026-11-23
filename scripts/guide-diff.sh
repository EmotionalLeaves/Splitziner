#!/usr/bin/env bash
# Compares the build guide text for every model in a folder between a git ref (default: HEAD) and the working tree.
# Usage: scripts/guide-diff.sh <models-dir> [ref]. Prints the models whose guide changed; the dumps stay in /tmp/gd_old and /tmp/gd_new.
set -euo pipefail
DIR=${1:?models dir}; REF=${2:-HEAD}
# File units: mm unless UNITS is set in the environment (UNITS="" lets the app detect them).
UNITS=${UNITS---units mm}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
rm -rf /tmp/gd_wt /tmp/gd_old /tmp/gd_new; mkdir -p /tmp/gd_old /tmp/gd_new
git -C "$ROOT" worktree add -f /tmp/gd_wt "$REF" -q
(cd /tmp/gd_wt && ANDROID_HOME= ./gradlew :cli:installDist --console=plain -q --max-workers=2 >/dev/null 2>&1)
(cd "$ROOT" && ANDROID_HOME= ./gradlew :cli:installDist --console=plain -q --max-workers=2 >/dev/null 2>&1)
for f in "$DIR"/*.stl; do
  m=$(basename "$f" .stl)
  GUIDETEXT=1 /tmp/gd_wt/cli/build/install/cli/bin/cli "$f" $UNITS --out /tmp/gd_o_$m --full --display in --fraction 2>&1 | grep -E '^(title|line|needs|hardware|item)' > /tmp/gd_old/$m.txt || true
  GUIDETEXT=1 "$ROOT/cli/build/install/cli/bin/cli" "$f" $UNITS --out /tmp/gd_n_$m --full --display in --fraction 2>&1 | grep -E '^(title|line|needs|hardware|item)' > /tmp/gd_new/$m.txt || true
  cmp -s /tmp/gd_old/$m.txt /tmp/gd_new/$m.txt || echo "changed $m"
done
git -C "$ROOT" worktree remove --force /tmp/gd_wt
