#!/usr/bin/env bash
# Runs the benchmark against a PostgreSQL pinned to a SINGLE CPU.
#
# Why this exists: the headline benchmark is measured on an 8-core M4, where DI002 is executed by
# parallel workers. The project scope quoted a "1-CPU test machine", and the two are not the same
# measurement. Rather than caveat the difference in prose, this reproduces it.
#
# What is constrained: the DATABASE container, via --cpus=1. That is the meaningful limit -- it
# removes parallel query workers, which is the specific advantage the M4 numbers enjoy. The JVM
# client is not constrained, because it spends essentially all of its time blocked on the server;
# constraining it would measure the wrong thing.
#
#   ./scripts/benchmark-1cpu.sh 10000000 5
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ROWS="${1:-10000000}"
REPEATS="${2:-5}"
CONTAINER="triage-postgres-1cpu"
PORT="${TRIAGE_1CPU_PORT:-55433}"
PASSWORD="triage_local_dev"

cleanup() {
  echo "Removing the single-CPU container."
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

echo "Starting PostgreSQL pinned to 1 CPU on port ${PORT}..."
docker run -d --name "$CONTAINER" \
  --cpus=1 \
  -e POSTGRES_USER=triage -e POSTGRES_PASSWORD="$PASSWORD" -e POSTGRES_DB=triage \
  -p "${PORT}:5432" \
  postgres:16-alpine \
  postgres -c shared_buffers=256MB -c work_mem=16MB -c maintenance_work_mem=256MB \
           -c max_connections=100 -c track_activity_query_size=4096 >/dev/null

echo -n "Waiting for it"
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER" pg_isready -U triage -d triage >/dev/null 2>&1; then echo " ready."; break; fi
  echo -n "."; sleep 1
done

# Confirm the limit really applied, rather than trusting the flag.
echo -n "CPU quota seen by the container: "
docker exec "$CONTAINER" sh -c 'nproc; cat /sys/fs/cgroup/cpu.max 2>/dev/null || true'

# sandbox-up.sh is not used here: it drives docker compose, which would start the ordinary
# 8-core sandbox rather than this CPU-limited container. Load the schema directly.
psql_1cpu() { docker exec -i -e PGPASSWORD="$PASSWORD" "$CONTAINER" psql -v ON_ERROR_STOP=1 -U triage -d triage "$@"; }
psql_1cpu -q < "$REPO_ROOT/db/schema.sql"
psql_1cpu -q < "$REPO_ROOT/db/generate.sql"

TRIAGE_CONTAINER="$CONTAINER" PGPORT="$PORT" SANDBOX_PASSWORD="$PASSWORD" \
  "$REPO_ROOT/scripts/benchmark.sh" "$ROWS" "$REPEATS"
