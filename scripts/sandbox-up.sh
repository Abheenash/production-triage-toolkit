#!/usr/bin/env bash
# Starts the throwaway PostgreSQL, loads the schema and the generator, and seeds it.
#
#   ./scripts/sandbox-up.sh            # 100,000 bookings, a few seconds
#   ./scripts/sandbox-up.sh 10000000   # 10 million, the benchmark dataset
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

ROWS="${1:-100000}"

echo "Starting the sandbox container..."
docker compose -f "$REPO_ROOT/docker-compose.yml" up -d

echo -n "Waiting for PostgreSQL to accept connections"
for _ in $(seq 1 60); do
  if [ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" = "healthy" ]; then
    echo " ready."
    break
  fi
  echo -n "."
  sleep 1
done

echo "Loading schema..."
psql_exec -q < "$REPO_ROOT/db/schema.sql"

echo "Loading the data generator..."
psql_exec -q < "$REPO_ROOT/db/generate.sql"

"$(dirname "${BASH_SOURCE[0]}")/seed.sh" "$ROWS"
