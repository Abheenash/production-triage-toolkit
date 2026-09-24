# Drill — 2026-09-24, against a real PostgreSQL 16

Everything below was executed, not asserted. PostgreSQL 16.15 in Docker, seeded
with the repo's own generator. No cloud account, no cost.

## Setup

```
docker compose up -d
psql -f db/schema.sql && psql -f db/generate.sql
psql -c "SELECT seed_workplace(100000);"
psql -f db/indexes.sql && psql -c "ANALYZE;"
```

| | |
|---|---|
| bookings | 100,000 |
| badge_scans | 10,000 |
| rooms | 220 |

## 1. Clean data

```
Checks    15 run, 15 passed, 0 with findings, 0 could not run
Elapsed   65 ms
Exit 0    healthy -- nothing at or above INFO was found
```

## 2. Six failure scenarios injected

Each `scenarios/*.sql` file breaks the data one specific way. Injected all six,
re-ran, and **exactly six checks reported — one per scenario, three rows each:**

| Severity | Check | Rows | Time | What it caught |
|---|---|---|---|---|
| HIGH | DI001 | 3 | 16 ms | Bookings pointing at a room that no longer exists |
| HIGH | DI002 | 3 | 20 ms | One room confirmed to two people at the same time |
| CRITICAL | DI003 | 3 | 5 ms | Active badge belonging to a terminated employee |
| MEDIUM | DI004 | 3 | 5 ms | Booking with more attendees than the room holds |
| CRITICAL | OPS001 | 3 | 2 ms | Sync job started and never finished |
| MEDIUM | OPS003 | 3 | 2 ms | Facility request past its SLA |

```
checksRun 15, passed 9, findings 6, couldNotRun 0, totalMatchedRows 18
bySeverity  CRITICAL 2, HIGH 2, MEDIUM 2, LOW 0, INFO 0
Exit 1      findings at or above INFO -- work the runbooks above
```

Nine checks still passed, which matters as much as the six that fired: an
injection that trips every check would mean the checks are not specific.

Every finding named its runbook.

## 3. The safety property, demonstrated

The README's central claim is that the tool cannot write. Three independent
pieces of evidence:

**Static.** All 15 shipped checks scanned for `insert|update|delete|drop|truncate|alter|grant` — none present.

**Runtime.** `SafetyIT` (8 tests, against this database) asserts the connection
itself refuses writes, that the session refuses to start if the server reports
itself writable, that a statement timeout cancels a slow check and reports it as
TIMEOUT rather than a pass, that a timeout does not leak into the next check, and
that the connection identifies itself in `pg_stat_activity`.

**Empirical.** A checksum over every booking row, before and after five further
full runs:

```
before  33f1756175d2fdd2fb024c57475158a7
after   33f1756175d2fdd2fb024c57475158a7
```

Byte-for-byte identical.

## 4. Timing

Three consecutive runs over 100,000 bookings with six findings present:
**81 ms, 87 ms, 80 ms.**

## 5. Documentation

`scripts/verify-docs.py`: **31/31 claims verified**, including that the README's
stated test counts match the suite that actually ran (132 unit + 44 integration =
176) and that the benchmark figures still appear where the README says they do.

## Test suite

```
mvn verify
  unit:        132 passed
  integration:  44 passed   (against this database)
  total:       176 passed, 0 failed
  spotbugs:    clean
BUILD SUCCESS
```

## What this drill does NOT show

It ran against a local container, not a production-shaped deployment — no
replication, no connection pooler, no concurrent write load competing for locks.
The `lock_timeout = 1000` behaviour in particular is only genuinely exercised when
something else holds a lock, and nothing here did. Criterion 5 (systemd unit
verification) is Linux-only and runs in CI rather than here.
