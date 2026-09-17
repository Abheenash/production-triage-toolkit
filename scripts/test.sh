#!/usr/bin/env bash
# Runs the whole suite: unit tests, then integration tests against a real PostgreSQL.
#
# Brings the sandbox up first if it is not already running. The integration tests use their own
# database (triage_it) on that server, so this never disturbs the demo data.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" >/dev/null 2>&1; then
  echo "Sandbox is not running; starting it."
  "$(dirname "${BASH_SOURCE[0]}")/sandbox-up.sh" >/dev/null
fi

export TRIAGE_IT_HOST="$PGHOST"
export TRIAGE_IT_PORT="$PGPORT"
export TRIAGE_IT_USER="$PGUSER"
export TRIAGE_IT_PASSWORD="$SANDBOX_PASSWORD"
export TRIAGE_IT_DB="${TRIAGE_IT_DB:-triage_it}"

cd "$REPO_ROOT"
exec mvn verify "$@"
