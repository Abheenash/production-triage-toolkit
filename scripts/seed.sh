#!/usr/bin/env bash
# Re-seeds the database with clean data and applies the tuning indexes.
#
# Indexes are created AFTER the rows are inserted, which is much faster than maintaining
# them row by row during the load, and is what you would do for a bulk load in production.
#
#   ./scripts/seed.sh 10000000
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_container

ROWS="${1:-100000}"

echo "Seeding ${ROWS} bookings (this also resets every table)..."
psql_exec -q -c "SELECT seed_workplace(${ROWS});" -t -A

echo "Applying the tuning indexes from db/indexes.sql..."
psql_exec -q < "$REPO_ROOT/db/indexes.sql"

echo "Collecting statistics..."
psql_exec -q -c "ANALYZE;"

psql_exec -t -A -c "
  SELECT 'bookings: ' || to_char(count(*), 'FM999,999,999') FROM bookings
  UNION ALL SELECT 'rooms: ' || count(*) FROM rooms
  UNION ALL SELECT 'employees: ' || count(*) FROM employees
  UNION ALL SELECT 'on disk: ' || pg_size_pretty(pg_database_size(current_database()));"
echo "Seeded. The database is clean -- all 15 checks should pass."
