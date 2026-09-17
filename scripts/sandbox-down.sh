#!/usr/bin/env bash
# Stops the sandbox and deletes its data volume. Nothing here is precious -- rebuild with
# ./scripts/sandbox-up.sh.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
docker compose -f "$REPO_ROOT/docker-compose.yml" down -v
echo "Sandbox removed."
