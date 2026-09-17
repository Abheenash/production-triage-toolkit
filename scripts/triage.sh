#!/usr/bin/env bash
# Runs the built CLI against the sandbox, building it first if needed.
# Any arguments are passed straight through:
#
#   ./scripts/triage.sh --format json
#   ./scripts/triage.sh --group dbhealth --verbose
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

JAR="$REPO_ROOT/target/triage.jar"
if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..." >&2
  (cd "$REPO_ROOT" && mvn -q package -DskipTests)
fi

# The sandbox password is supplied here only because the target IS the local sandbox.
# Against a real database, export PGPASSWORD yourself and this line is a no-op.
export PGPASSWORD="${PGPASSWORD:-$SANDBOX_PASSWORD}"

exec java -jar "$JAR" \
  --host "$PGHOST" --port "$PGPORT" --database "$PGDATABASE" --user "$PGUSER" "$@"
