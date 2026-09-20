# Production Triage Toolkit — Engineering Case Study

## Context

Production support teams usually learn about data problems from users: a room booked twice, a
badge that still works after someone leaves, a sync job that quietly stopped. The checks that do
exist are typically ad-hoc SQL saved on somebody's laptop, with no severity, no runbook, and no
guarantee they are safe to run against production.

I built a Java CLI that runs a library of read-only diagnostics, ranks findings by severity, shows
sample rows, and links each one to a runbook. It runs against a workplace-booking system — sites,
rooms, bookings, badge scans, facility requests, a calendar-sync job — with a generator that
scales to 10 million bookings and six injectable failures that prove each check works.

**A full 15-check run takes 1,069 ms against 10 million bookings, and 1,228 ms with the database
pinned to a single CPU.**

## My role

Sole engineer. Schema, the fifteen checks, the CLI, the data generator, the failure scenarios, all
fifteen runbooks, the test suite, the CI pipeline, the benchmark harness, and the deployment
artifacts.

## Architecture

```
triage --host db --check DI002 --format json
            │
   TriageCommand ──── CheckCatalog ──── 15 .sql files on the classpath
            │              │            + thresholds substituted
            │              └─────────── SqlSafety: static write-keyword scan
            │
   ConnectionFactory ───── read-only session, VERIFIED not assumed
            │
   TriageRunner ────────── one short read-only transaction per check,
            │              SET LOCAL statement_timeout, always rolled back
            │
   RunReport ───────────── rank by severity, then count, then id
            │
   Text │ JSON │ Prompt ── exit 0 / 1 / 2
```

The check SQL lives in `.sql` files rather than Java strings so that someone on call can read it,
review it as SQL, and paste it into psql. `--show-sql DI002` prints the exact text with thresholds
already applied.

## Problems I had to solve

### 1. Being safe enough to point at production

"Safe to run against production at any time" is the objective that does real damage if it is only
believed, so it does not rest on one mechanism:

| Layer | Stops |
|---|---|
| Static scan at load time | A check containing a write, a second statement, or `pg_terminate_backend` |
| Read-only session | Any write, whatever the SQL says |
| **Verified, not assumed** | The tool asks the server `SHOW transaction_read_only` and **closes the connection** if the answer is not `on` |
| `SET LOCAL statement_timeout` per check | A check holding resources on a struggling server |

Plus `lock_timeout` of one second, so a diagnostic never joins the lock queue it is reporting on,
and short transactions always rolled back, so the tool reporting DBH005 (idle in transaction) can
never be the cause of it.

There is deliberately **no `--password` option**. A password on the command line is visible in
`ps` to every user on the host. It is read from the environment or not at all.

### 2. Making "clean data" a guarantee rather than a hope

The six scenarios only mean something if the baseline is genuinely clean — a finding after
injection must have come from the injection.

The generator uses **no randomness at all**: every value is a function of the row ordinal, so the
same input produces byte-identical data. More importantly, bookings are laid on a deterministic
(room, time-slot) grid rather than sampled. Room *r* gets a booking every four hours, phase-shifted
by *r*, each at most 90 minutes long. Two confirmed bookings in one room therefore **cannot**
overlap. DI002 is clean by construction, not by luck.

Random timestamps would collide constantly at 10 million rows, and the clean baseline would be
worthless.

### 3. Never letting a broken check look like a healthy database

A check that returns nothing because it is broken is indistinguishable from a healthy database.
That shaped three decisions:

- **Exit code 2 outranks every finding.** One check that could not run gives exit 2 even alongside
  a CRITICAL finding, because "I could not look" and "I looked and found problems" must never be
  the same signal.
- **Passing checks are listed explicitly** in the JSON, so "DI003 ran and found nothing" is
  distinguishable from "DI003 is missing because it crashed".
- **Every check is tested to fire.** Covered in the war stories below, because I got this wrong
  first.

## Debugging war stories

### The index that made it four times slower

DI002 — one room confirmed to two people at once — was written the obvious way: a self-join
pairing every confirmed booking with every other in the same room. I added the index that
obviously supported it, a partial index on `(room_id, starts_at, ends_at) WHERE status = 'confirmed'`.

Measured, the tuned configuration was **slower than no indexes at all**: 2,647 ms against 2,154 ms.

`EXPLAIN (ANALYZE, BUFFERS)` said why:

```
Nested Loop  (actual time=1913.181..1913.182 rows=0 loops=3)
  ->  Parallel Index Scan using bookings_starts_at_idx on bookings a   (rows=303583)
  ->  Index Scan using bookings_confirmed_room_time_idx on bookings b  (loops=910750)
        Buffers: shared hit=5757218
```

The index was working perfectly, and that was the problem. It let the planner replace a single
bulk hash join with **910,750 individual index probes** — one per confirmed booking in the 7-day
window — for 5.7 million buffer hits. Each probe is fast. Nearly a million of them is not.

The fix was not a better index. It was noticing the join was unnecessary. Walking one room's
bookings in start order, a booking overlaps something earlier **if and only if it starts before
the latest end seen so far in that room** — a running maximum over a sorted pass:

```sql
max(ends_at) OVER (PARTITION BY room_id ORDER BY starts_at, booking_id
                   ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_max_end
...
WHERE prior_max_end > starts_at
```

O(n²) in the worst case became O(n log n), dominated by one sort. **2,147 ms to 526 ms.**

The running maximum matters and the obvious simplification is wrong: a `lag()` comparing each
booking to its immediate predecessor misses a third booking that clashes with a long first one but
not with the short second one in between.

Finding the clashes is now cheap; identifying *what* each collides with still needs the expensive
lookup, but it runs once per finding instead of once per booking — and on a healthy database there
are no findings, so it runs not at all.

**What I took from it:** the benchmark measures an untuned *and* a tuned pass. Had it only measured
the tuned one, a 2.6-second run would have looked respectable and the regression would have
shipped invisibly.

### Four indexes doing nothing, and one that looked identical but wasn't

With DI002 rewritten, I measured index usage per check instead of assuming — reset
`pg_stat_user_indexes`, run one check, read `idx_scan`.

Four of nine indexes recorded **zero scans**, totalling about 410 MB, including a 301 MB index I
had been confident about. They were removed; the run time did not change and the database shrank.

A fifth, `badge_scans_badge_time_idx`, also recorded zero scans — and deleting it would have been
a mistake. DI003's lateral lookup ("has this badge been used since the employee left?") runs once
per ghost badge, and a healthy database has none. Zero scans is what a *working* system looks like.

Measured with three ghost badges injected:

| | DI003 duration |
|---|---|
| With the index | **16 ms** |
| Without it | 240 ms |

It costs nothing when nothing is wrong and stops the check slowing down exactly when it has
something to report. Judging indexes on healthy-system scan counts alone would have thrown it away.

### Nine checks that could never have failed a test

After shipping, I audited what the suite actually proved and found the worst bug in the project —
in the tests, not the code.

**Only 6 of 15 checks were ever proven to fire.** The six scenarios covered DI001–DI004, OPS001 and
OPS003. The other nine were only ever asserted to return *zero rows on clean data* — which is
exactly what a check with a typo in its `WHERE` clause does, forever, while looking healthy.

The tool is built around the principle that a broken check must never read as a healthy database,
and its own test suite had that hole.

`CheckFiresIT` now creates the real condition for each of the nine. The five database-health checks
cannot be faked — they read `pg_stat_activity` and `pg_blocking_pids()` — so the tests genuinely
saturate connections, leave a query executing, block one session behind another's lock, accumulate
dead tuples with autovacuum disabled on one table, and abandon a session inside an open
transaction.

Verified by sabotage: making DI006 match nothing now fails the build. Before, it passed everything.

### A test that was right for the wrong reason

The DBH002 test (a query running too long) passed alone and failed in the full suite.
`pg_stat_activity` is **cluster-wide**, so the deliberately-slow query another test class uses was
indistinguishable from this one's, and a backend left behind by either poisoned the other.

Fixed by giving the probe a marker comment, cancelling strays before and after every test, and
re-confirming the probe is still running when the assertion fails — so a dead probe reports as a
dead probe rather than as a broken check. Verified over six consecutive full runs.

### Documentation that described code that no longer existed

The DI002 runbook said "each row in the finding is one *pair*". True of the self-join; false after
the window-function rewrite, which emits one row per booking that starts into an earlier one.

Every link resolved and every count was right — the *description* was simply wrong, which is the
kind of drift no link checker catches. I measured the real behaviour (three mutually overlapping
bookings produce **two** rows, not three), corrected the runbook, and pinned it with a test so the
prose and the query cannot separate again.

## Trade-offs

**Deterministic generator over realistic randomness.** Perfectly regular bookings are less lifelike
than sampled ones. I took reproducibility and a provably clean baseline over realism, because a
benchmark I cannot repeat and a baseline I cannot trust are both worthless.

**Thresholds shipped in v1, though scoped for v2.** The 7-day booking window already needed a
substitution mechanism, so exposing the other seven knobs cost one CLI option. Values must parse as
non-negative numbers, so `--threshold 'booking_window_days=7; DROP TABLE bookings'` fails with "must
be a number" — and there is a test for it.

**Three constraints deliberately missing from the schema.** No foreign key on `bookings.room_id`,
no `CHECK (ends_at > starts_at)`, no unique index on `external_event_id`. They are the three that
real high-write tables most often lack, their absence is what lets DI001, DI005 and DI007 have
anything to find, and all three runbooks prescribe adding them — with `NOT VALID` then `VALIDATE`,
so it does not lock a 10-million-row table.

**Grounding shipped, inference left out.** `--format prompt` emits every finding, its rows, and the
full runbook text as grounded context, but the tool never calls a model. A diagnostic pointed at
production should not acquire an outbound dependency, findings contain real row data whose
destination is the operator's compliance decision, and exit codes 0/1/2 are a contract that nothing
non-deterministic belongs upstream of. Reasoning in [docs/genai.md](docs/genai.md).

**Testcontainers removed.** Docker 29's API rejects the client library's `/info` call, so
container-per-test could not start at all. The integration tests now take connection details from
environment variables, supplied by Docker Compose locally and a service container in CI — less
coupling, faster, and it works everywhere.

## Evidence

| Claim | Where |
|---|---|
| 1,069 ms at 10M; 1,228 ms on one CPU | [docs/benchmark.md](docs/benchmark.md), raw JSON in [benchmark/results/](benchmark/results/) |
| The tuned-slower-than-untuned regression | [benchmark/results/](benchmark/results/) — the BEFORE file is kept deliberately |
| Clean data → 15 pass, exit 0 | CI criterion 1 |
| Six scenarios → exactly six checks, exit 1 | CI criterion 2 |
| No check can write | CI criterion 3, plus `SafetyIT` |
| Documentation is still true | CI criterion 4, `scripts/verify-docs.py` |
| Every check proven to fire | `CheckFiresIT` |
| Deployment artifacts are valid | CI: `systemd-analyze verify`, `kubeconform -strict`, `terraform validate` |

## What I would improve next

1. ~~**Correlation across runs.**~~ Shipped (Sep 2026): `--compare` and `--history-dir` classify every
   check as NEW / RESOLVED / WORSENED / IMPROVED / UNCHANGED / BROKE / RECOVERED against a previous
   JSON report, and `--fail-on-regression` makes a scheduled run exit 1 only for what got worse. The
   integration test runs the CLI three times against the real database -- clean, with a scenario
   injected, and again -- and asserts NEW, then UNCHANGED with exit 0. What remains of the idea is a
   trend over the last N runs rather than a pair.
2. ~~**Publish metrics rather than text.**~~ Shipped (Sep 2026): `--format prometheus` emits the
   exposition format for the node_exporter textfile collector — per-check matches, ran/not-ran,
   duration, run exit code, and change counts when comparing. What remains is a CloudWatch EMF
   variant for hosts without node_exporter.
3. **MySQL.** The data-integrity and operations checks port directly; the five database-health ones
   are PostgreSQL-specific and would need genuine rewrites against `performance_schema`.
4. **Per-priority SLA thresholds for OPS003.** Currently a `CASE` expression in the query. It should
   be configurable like the other eight knobs.
