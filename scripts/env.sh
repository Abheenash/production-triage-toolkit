#!/usr/bin/env bash
# Shared settings for the sandbox scripts. Every value can be overridden from the
# environment, so the same scripts work against the Docker sandbox and against a real
# database without editing anything:
#
#   PGHOST=db.internal PGPORT=5432 PGDATABASE=bookings PGUSER=readonly ./scripts/triage.sh
#
# The password is never set here. It is read from PGPASSWORD at the point of use, which is
# the only place the toolkit will look for it.
set -euo pipefail

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-55432}"
export PGDATABASE="${PGDATABASE:-triage}"
export PGUSER="${PGUSER:-triage}"

# The sandbox password, used only when talking to the local Docker container. A real target
# must supply its own PGPASSWORD; nothing here will invent one.
SANDBOX_PASSWORD="${SANDBOX_PASSWORD:-triage_local_dev}"
CONTAINER="${TRIAGE_CONTAINER:-triage-postgres}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export REPO_ROOT

# Run psql inside the container, so nothing on the host needs a postgres client installed.
psql_exec() {
  docker exec -i -e PGPASSWORD="$SANDBOX_PASSWORD" "$CONTAINER" \
    psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE" "$@"
}

require_container() {
  if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" >/dev/null 2>&1; then
    echo "The sandbox container '$CONTAINER' is not running. Start it with:" >&2
    echo "  ./scripts/sandbox-up.sh" >&2
    exit 2
  fi
}
