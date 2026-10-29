#!/usr/bin/env bash
# Verify gate for the sync server (server/): install the locked
# dependencies, then lint, typecheck and run the unit tests. The app's
# Gradle gate is separate (see scripts/release.sh). server/Dockerfile runs
# the same `pnpm check`, so an image is never built from a failing tree.
#
# Usage: bash scripts/server-gate.sh

set -euo pipefail

# Resolve the repo root from this script's location so the gate works
# regardless of the caller's cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../server"

if ! command -v pnpm >/dev/null 2>&1; then
  echo "server-gate: pnpm not found; run scripts/setup-toolchain.sh and source scripts/env.sh" >&2
  exit 1
fi

pnpm install --frozen-lockfile
pnpm check
