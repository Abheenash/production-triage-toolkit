#!/usr/bin/env bash
# Measures a full 15-check run, twice: once WITHOUT the tuning indexes in db/indexes.sql, and
# once with them. Both passes run against identical data on the same server in the same session,
# so the difference between them is the tuning and nothing else.
#
#   ./scripts/benchmark.sh              # 10,000,000 bookings, 5 runs per pass
#   ./scripts/benchmark.sh 1000000 3    # smaller and quicker
#
# Writes benchmark/results/<rows>-<timestamp>.json and prints a Markdown table.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_container

ROWS="${1:-10000000}"
REPEATS="${2:-5}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$REPO_ROOT/benchmark/results/${ROWS}-${STAMP}.json"
JAR="$REPO_ROOT/target/triage.jar"

[ -f "$JAR" ] || (cd "$REPO_ROOT" && mvn -q package -DskipTests)
export PGPASSWORD="${PGPASSWORD:-$SANDBOX_PASSWORD}"

run_triage() {
  # A generous timeout only for the benchmark. The untuned pass is deliberately slow -- with the
  # shipped default of 5s several of its checks would be cancelled, and a cancelled check cannot
  # be timed. The timeout caps a query; it never makes one faster, so it does not flatter either pass.
  java -jar "$JAR" --host "$PGHOST" --port "$PGPORT" --database "$PGDATABASE" \
       --user "$PGUSER" --format json --no-color --sample-rows 5 --timeout-ms 300000 || true
}

echo "Seeding ${ROWS} bookings. This is the slow part."
time psql_exec -q -t -A -c "SELECT seed_workplace(${ROWS});"

echo
echo "=== Pass 1: WITHOUT the tuning indexes ==="
psql_exec -q < "$REPO_ROOT/db/drop-indexes.sql"
psql_exec -q -c "ANALYZE;"
# One discarded warm-up per pass, so the measurements compare warm cache against warm cache
# rather than measuring which pass happened to run first.
run_triage > /dev/null
for i in $(seq 1 "$REPEATS"); do
  run_triage > "/tmp/triage-untuned-$i.json"
  printf "  run %d: %s ms\n" "$i" "$(python3 -c "import json;print(json.load(open('/tmp/triage-untuned-$i.json'))['durationMs'])")"
done

echo
echo "=== Pass 2: WITH the tuning indexes ==="
psql_exec -q < "$REPO_ROOT/db/indexes.sql"
psql_exec -q -c "ANALYZE;"
run_triage > /dev/null
for i in $(seq 1 "$REPEATS"); do
  run_triage > "/tmp/triage-tuned-$i.json"
  printf "  run %d: %s ms\n" "$i" "$(python3 -c "import json;print(json.load(open('/tmp/triage-tuned-$i.json'))['durationMs'])")"
done

SIZE=$(psql_exec -t -A -c "SELECT pg_size_pretty(pg_database_size(current_database()));")
PGVER=$(psql_exec -t -A -c "SHOW server_version;")

ROWS="$ROWS" REPEATS="$REPEATS" OUT="$OUT" SIZE="$SIZE" PGVER="$PGVER" STAMP="$STAMP" \
python3 "$REPO_ROOT/scripts/benchmark_report.py"

echo
echo "Wrote $OUT"
