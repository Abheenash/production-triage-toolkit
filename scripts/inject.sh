#!/usr/bin/env bash
# Injects failure scenarios so the checks have something to find.
#
#   ./scripts/inject.sh            # all six
#   ./scripts/inject.sh 02 05      # only those two, by number
#
# Scenarios are independent: any subset, in any order, produces findings only from the
# checks that subset maps to. Undo by re-running ./scripts/seed.sh.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_container

shopt -s nullglob
if [ "$#" -eq 0 ]; then
  FILES=("$REPO_ROOT"/scenarios/*.sql)
else
  FILES=()
  for n in "$@"; do
    matches=("$REPO_ROOT"/scenarios/"${n}"-*.sql)
    if [ ${#matches[@]} -eq 0 ]; then
      echo "No scenario numbered '${n}'. Available:" >&2
      for f in "$REPO_ROOT"/scenarios/*.sql; do echo "  $(basename "$f")" >&2; done
      exit 2
    fi
    FILES+=("${matches[@]}")
  done
fi

for f in "${FILES[@]}"; do
  echo "Injecting $(basename "$f")"
  psql_exec -q < "$f"
done
echo "Done. Run ./scripts/triage.sh to see what the checks make of it."
