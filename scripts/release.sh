#!/usr/bin/env bash
# One-command release: scripts/release.sh <version> "<title>" <notes-file> ["commit message"]
# Adds a changelog entry written for users, commits everything staged or not, and pushes.
# The Release workflow sees the new top heading and publishes the APK.
set -euo pipefail
VERSION="$1"; TITLE="$2"; NOTES="$3"; MSG="${4:-Release v$VERSION: $TITLE}"
cd "$(dirname "$0")/.."
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must be X.Y.Z"; exit 1; }
grep -q "^## v$VERSION" CHANGELOG.md && { echo "v$VERSION is already in CHANGELOG.md"; exit 1; }
{
  echo "# Changelog"
  echo
  echo "## v$VERSION - $TITLE"
  echo
  cat "$NOTES"
  echo
  tail -n +3 CHANGELOG.md
} > CHANGELOG.tmp && mv CHANGELOG.tmp CHANGELOG.md
git add -A
git commit -q -m "$MSG" -m "Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01L2awDHVcadkP4yGdqFTruj"
git -c push.negotiate=true push -q -u origin main
echo "pushed v$VERSION ($(git rev-parse --short HEAD))"
