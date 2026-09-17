# Benchmark

Every number on this page was measured by `scripts/benchmark.sh` on the machine described below.
Nothing here is estimated. The raw per-run timings are committed under `benchmark/results/` so the
spread is visible rather than summarised away.

## How it is measured

`scripts/benchmark.sh <rows> <repeats>` does the following:

1. Seeds the database to `<rows>` bookings with the deterministic generator, so both passes see
   byte-identical data.
2. **Pass 1 (untuned):** drops everything in `db/indexes.sql`, runs `ANALYZE`, discards one
   warm-up run, then times `<repeats>` full 15-check runs.
3. **Pass 2 (tuned):** creates those indexes, runs `ANALYZE`, discards one warm-up run, then times
   `<repeats>` more.
4. Reports the **median**, because a single scheduling hiccup on a laptop distorts a mean of five
   runs and the median is what a typical run actually costs.

Both passes run against the same data, on the same server, in the same session, minutes apart.
The only difference between them is the index layer.

The timing is the tool's own `durationMs`: the wall time of all 15 checks, excluding JVM startup
and connection setup. JVM startup adds roughly 250 ms on top for a real invocation.

The benchmark raises `--timeout-ms` to 300,000. The untuned pass is deliberately slow and several
of its checks would be cancelled by the shipped 5-second default, and a cancelled check cannot be
timed. A timeout caps a query; it never makes one faster, so this does not flatter either pass.

## Hardware and configuration

| | |
|---|---|
| Machine | MacBook Air M4, 16 GB unified memory, macOS 27 |
| PostgreSQL | 16.15 (`postgres:16-alpine`) in Docker Desktop |
| `shared_buffers` | 256 MB |
| `work_mem` | 16 MB |
| `maintenance_work_mem` | 256 MB |
| `max_connections` | 100 |

The server settings are deliberately modest -- roughly a small cloud instance rather than a
workstation given all of a laptop's memory. A database tuned generously would make these numbers
look better without making the tool faster.

## Headline result

**10,000,000 bookings, 1,746 MB on disk. A full 15-check run takes 1,069 ms.**

| Dataset | On disk | Untuned | Tuned | Speedup |
|---|---:|---:|---:|---:|
| 100,000 bookings | 27 MB | 238 ms | **79 ms** | 3.0x |
| 1,000,000 bookings | 183 MB | 446 ms | **195 ms** | 2.3x |
| 10,000,000 bookings | 1,746 MB | 2,259 ms | **1,069 ms** | 2.1x |

Median of 5 runs at each size. Spread at 10M: untuned 2,212-2,295 ms, tuned 1,025-1,101 ms.

A hundredfold increase in data costs about thirteen times the wall time. That is not because the
checks got cleverer as they scaled -- it is because the ones that dominate are bounded by the
7-day booking window rather than by table size, so most of the growth is in the fixed cost of
scanning a wider window, not in scanning more history.

## Per-check timings at 10 million bookings

| Check | Untuned | Tuned | Speedup |
|---|---:|---:|---:|
| DI001 orphaned bookings | 376 ms | 69 ms | 5.4x |
| DI002 overlapping bookings | 685 ms | **526 ms** | 1.3x |
| DI003 ghost badges | 93 ms | 16 ms | 5.8x |
| DI004 over capacity | 401 ms | 66 ms | 6.1x |
| DI005 invalid time range | 143 ms | 42 ms | 3.4x |
| DI006 inactive rooms | 203 ms | 2 ms | 101.5x |
| DI007 duplicate events | 334 ms | 291 ms | 1.1x |
| OPS001 stuck sync job | 5 ms | 4 ms | 1.2x |
| OPS002 stale sync job | 4 ms | 3 ms | 1.3x |
| OPS003 facility SLA | 4 ms | 3 ms | 1.3x |
| DBH001-DBH005 | 2-5 ms each | 2-4 ms each | ~1x |

The five database-health checks read `pg_stat_activity` and `pg_stat_user_tables`, whose cost
depends on how many sessions exist rather than on how much data there is. They do not change with
scale and they are not worth tuning.

**DI002 is half the remaining run time.** It is the check with genuine work to do, and the section
below is about how it got there.

## The tuning, in the order it actually happened

### First working version: 2,647 ms, and the indexes made it worse

The first complete version wrote DI002 the obvious way -- a self-join pairing every confirmed
booking with every other booking in the same room -- and shipped nine indexes chosen by reading
the queries and deciding what looked useful.

Measured, it was **slower with those indexes than without them**: 2,647 ms tuned against 2,154 ms
untuned. That is the whole reason the benchmark runs both passes. Had it only ever measured the
tuned configuration, the result would have looked like a respectable 2.6 seconds and the
regression would have shipped.

`EXPLAIN (ANALYZE, BUFFERS)` on DI002 said why:

```
Nested Loop  (actual time=1913.181..1913.182 rows=0 loops=3)
  ->  Parallel Index Scan using bookings_starts_at_idx on bookings a   (rows=303583)
  ->  Index Scan using bookings_confirmed_room_time_idx on bookings b  (loops=910750)
        Buffers: shared hit=5757218
```

The partial index on `(room_id, starts_at, ends_at)` was doing its job perfectly, and that was the
problem. It let the planner replace a single bulk hash join with **910,750 individual index
probes** -- one per confirmed booking in the 7-day window -- for 5.7 million buffer hits. Each
probe is fast. Nearly a million of them is not.

### Rewriting DI002: 2,147 ms to 526 ms

The fix was not a better index. It was noticing that the join is unnecessary.

Walking one room's bookings in start order, a booking overlaps something earlier **if and only if
it starts before the latest end seen so far in that room**. That is a running maximum over a
sorted pass -- one window function, no join:

```sql
max(ends_at) OVER (PARTITION BY room_id ORDER BY starts_at, booking_id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_max_end
...
WHERE prior_max_end > starts_at
```

The running maximum matters, and the obvious simplification is wrong. A `lag()` comparing each
booking to its immediate predecessor misses this case:

```
room 4   |-------- A, 3 hours --------|
             |-- B --|
                          |-- C --|          C's predecessor is B, and C does not touch B.
                                             But C clashes with A. lag() misses it.
```

Comparing against the maximum of every earlier end catches C. Comparing against the previous row
does not.

Finding the clashing bookings is now O(n log n): one index scan of the window, one sort, one pass.
Identifying *what* each one collides with still needs the expensive lookup -- but it now runs once
per finding instead of once per booking, and on a healthy database there are no findings, so it
runs not at all.

The plan afterwards:

```
Sort (actual time=548.952..548.957 rows=0)
  ->  WindowAgg (rows=910750)
        ->  Sort (rows=910750, external merge, Disk: 44560kB)
              ->  Index Scan using bookings_starts_at_idx (rows=910750)
```

The remaining 526 ms is that sort of 910,750 rows, which spills to disk at `work_mem = 16MB`. A
server with a larger `work_mem` will do it in memory and be measurably faster; this is the one
check whose timing is sensitive to that setting.

### Pruning the indexes: 410 MB returned, no time lost

With DI002 rewritten, index usage was measured per check rather than assumed. The method is in
`db/indexes.sql`: reset `pg_stat_user_indexes`, run one check, read `idx_scan`.

| Check | Indexes actually used |
|---|---|
| DI001 | `bookings_starts_at_idx`, `rooms_pkey` |
| DI002 | `bookings_starts_at_idx`, `rooms_pkey` |
| DI003 | `employees_terminated_idx`, `employees_pkey` |
| DI004 | `bookings_starts_at_idx`, `rooms_pkey` |
| DI005 | `bookings_starts_at_idx` |
| DI006 | `bookings_confirmed_room_time_idx`, `rooms_pkey` |
| DI007 | `bookings_starts_at_idx` |
| OPS001, OPS002 | none -- sequential scan of a 1,440-row table, correctly |
| OPS003 | `facility_requests_open_idx` |

Four of the nine indexes recorded **zero scans**, totalling about 410 MB:

- `bookings_starts_room_idx` (301 MB) -- the planner preferred `bookings_starts_at_idx` plus a
  heap fetch over maintaining a second copy of the same range.
- `bookings_external_event_idx` (77 MB) -- DI007 filters by the booking window first, which the
  existing index already serves. Adding this one moved DI007 by about 5 ms.
- `badges_active_employee_idx` (440 kB) and `sync_job_runs_name_status_idx` (88 kB) -- both tables
  are small enough that a sequential scan is the right plan.

They were removed. The database went from 2,125 MB to 1,746 MB and the run time did not change,
which is the definition of an index that was never earning anything -- while still costing write
amplification on every insert into the hot table.

### The index that looked dead and was not

`badge_scans_badge_time_idx` also recorded zero scans, and deleting it would have been a mistake.

DI003's lateral lookup -- has this badge been used since the employee left? -- runs once per ghost
badge, and a healthy database has none. Zero scans is exactly what a *working* system should show.

Measured with three ghost badges injected:

| | DI003 duration |
|---|---|
| With `badge_scans_badge_time_idx` | **16 ms** |
| Without it | 240 ms |

It costs nothing when nothing is wrong, and it stops the check slowing down at precisely the
moment it has something to report. Judging indexes on healthy-system scan counts alone would have
thrown it away.

## Summary of the tuning

| Version | Full run at 10M |
|---|---:|
| Untuned baseline (no tuning indexes) | 2,259 ms |
| First working version (self-join DI002, 9 indexes) | 2,647 ms |
| **Current (window-function DI002, 5 measured indexes)** | **1,069 ms** |

**2.5x faster than the first version that worked, and 2.1x faster than no tuning at all.**

The largest single gain did not come from an index. It came from replacing a join with a sort,
which was only visible because the benchmark measured both configurations and the regression could
not hide.

## Reproducing this

```bash
./scripts/sandbox-up.sh          # starts PostgreSQL, loads the schema
./scripts/benchmark.sh 10000000 5
```

Seeding 10 million bookings takes about 43 seconds and needs roughly 2 GB of disk. The benchmark
itself takes a couple of minutes. Results land in `benchmark/results/` as JSON.

## A note on the original target

The project scope set out to reach "under 2 seconds at 10 million bookings, measured on a 1-CPU
test machine." The measured result here -- 1,069 ms -- meets that bar, but on an M4 laptop rather
than a 1-CPU machine, so it is not the same measurement. DI002 uses parallel workers and would
lose that advantage on a single core; expect a single-CPU run to be meaningfully slower. The
numbers on this page describe the hardware named above and nothing else.
