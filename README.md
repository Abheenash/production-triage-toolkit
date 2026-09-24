# Production Triage Toolkit

> **Sep 2026:** the full suite **executed against a real PostgreSQL 16** for the first time — 176 tests (132 unit + 44 integration), six failure scenarios injected and exactly six checks fired, and the read-only claim demonstrated three ways including a byte-for-byte checksum across five full runs. [docs/drills/2026-09-24-local-postgres.md](docs/drills/2026-09-24-local-postgres.md)
>
> **Sep 2026:** run-to-run comparison (`--compare`, `--history-dir`, `--fail-on-regression`) and `--format prometheus`; 176 tests, 87.2% coverage.

**A Java CLI that runs 15 read-only SQL diagnostics against PostgreSQL, ranks findings by
severity, and links each one to a runbook — tested with injected failures and tuned for 10M-row
datasets.**

Production support teams usually learn about data problems from users: a room booked twice, a
badge that still works after someone leaves, a sync job that quietly stopped. This tool finds them
first. It runs a library of read-only diagnostics, ranks each finding by severity, shows sample
rows, and points at a runbook that explains how to confirm and fix the problem safely.

It runs against a sample workplace-booking system — sites, rooms, bookings, badge scans, facility
requests, and a calendar-sync job — with a generator that scales to 10 million bookings. Six
injectable failure scenarios prove each check actually works. Because the tool is pointed at
production, safety is part of the design: every query runs read-only, under a time limit, from a
named session, with a password read only from the environment.

**A full 15-check run takes 1,069 ms against 10 million bookings** — and 1,228 ms with the
database pinned to a single CPU. [Measured, with the tuning story](docs/benchmark.md).

The engineering decisions, the debugging, and what I got wrong first are in
[CASE_STUDY.md](CASE_STUDY.md).

---

## What it looks like

```
Production Triage Toolkit 1.0.0
  Target    triage@localhost:55432/triage (PostgreSQL 16.15)
  Started   2026-09-17T04:49:52.252685Z
  Checks    15 run, 13 passed, 2 with findings, 0 could not run
  Elapsed   81 ms

FINDINGS  -- most urgent first

  CRITICAL  DI003   Active badge belonging to a terminated employee
            3 matching rows, checked in 4 ms
            Impact   Someone who has left the company can still open the doors.
            Runbook  runbooks/DI003-ghost-badges.md

            badge_id   badge_number   employee_id   full_name     terminated_at                 days_since_termination   scans_since_termination   (+2 more columns)
            --------   ------------   -----------   -----------   ---------------------------   ----------------------   -----------------------
            25         BDG-0000025    25            Employee 25   2025-08-15T04:49:44.528372Z   398.0                    1
            50         BDG-0000050    50            Employee 50   2025-08-26T04:49:44.528372Z   387.0                    0
            75         BDG-0000075    75            Employee 75   2026-05-18T04:49:44.528372Z   122.0                    0

  CRITICAL  OPS001  Sync job started and never finished
            3 matching rows, checked in 2 ms
            Impact   New calendar events stop appearing, silently, with no error anywhere.
            Runbook  runbooks/OPS001-stuck-sync-job.md

            run_id   job_name        stuck_reason                         running_minutes   heartbeat_age_minutes   rows_processed   (+2 more columns)
            ------   -------------   ----------------------------------   ---------------   ---------------------   --------------
            1443     calendar_sync   alive but running past its expe...   240.0             0.3                     96412
            1442     calendar_sync   never sent a heartbeat               180.0             180.0                   0
            1441     calendar_sync   heartbeat stopped advancing          95.0              70.0                    1840

  Passed: DBH001, DBH002, DBH003, DBH004, DBH005, DI001, DI002, DI004, DI005, DI006, DI007, OPS002, OPS003

  Exit 1  findings at or above INFO -- work the runbooks above
```

Two details in that output are the whole argument of the project.

`scans_since_termination` separates **a hole that exists** from **a hole somebody is walking
through**. Badge 25 has been used since its owner left; the other two have not. Same severity,
completely different phone call.

`stuck_reason` distinguishes three ways a job gets stuck, because they need opposite responses. A
run that never sent a heartbeat is dead and its row can be cleared. A run that is *alive and
overrunning* is still writing — clear its row and a second run starts alongside it, and now you
have duplicate calendar events too.

---

## Try it in two minutes

Needs Docker, JDK 17+ and Maven.

```bash
./scripts/sandbox-up.sh          # PostgreSQL + schema + 100,000 bookings
./scripts/triage.sh              # exit 0 -- all 15 checks pass

./scripts/inject.sh              # break six things
./scripts/triage.sh              # exit 1 -- exactly six checks report

./scripts/seed.sh                # back to clean
```

Other things worth trying:

```bash
./scripts/triage.sh --format json | jq '.findings[] | {checkId, severity, matchCount, runbook}'
./scripts/triage.sh --format prompt | pbcopy   # grounded context for an assistant
./scripts/triage.sh --group dbhealth --verbose
./scripts/triage.sh --check DI002 --sample-rows 20
./scripts/triage.sh --fail-on HIGH          # report everything, exit 1 only for HIGH and above
java -jar target/triage.jar --show-sql DI002    # read the query before you trust it
java -jar target/triage.jar --list-checks
```

Run the tests, or the benchmark:

```bash
./scripts/test.sh                # 132 unit + 44 integration tests against a real PostgreSQL
./scripts/benchmark.sh 10000000 5
./scripts/benchmark-1cpu.sh 10000000 5   # same, against a database pinned to one CPU
```

---

## The 15 checks

### Data integrity (7)

| ID | Severity | Finds | What a user notices |
|---|---|---|---|
| [DI001](runbooks/DI001-orphaned-bookings.md) | HIGH | Bookings pointing at a deleted room | A blank room name, or an error opening the booking |
| [DI002](runbooks/DI002-overlapping-bookings.md) | HIGH | One room confirmed to two people at once | Two groups arrive, one has to leave |
| [DI003](runbooks/DI003-ghost-badges.md) | CRITICAL | Active badge, terminated employee | Nothing. That is the problem |
| [DI004](runbooks/DI004-over-capacity.md) | MEDIUM | More attendees than the room holds | People arrive and cannot sit |
| [DI005](runbooks/DI005-invalid-time-range.md) | HIGH | A booking that ends before it starts | A room shows free when it is not |
| [DI006](runbooks/DI006-inactive-rooms.md) | MEDIUM | Future booking in a decommissioned room | Someone walks to a building site |
| [DI007](runbooks/DI007-duplicate-events.md) | HIGH | Calendar sync wrote an event twice | A meeting appears twice; rooms look busy |

### Operations (3)

| ID | Severity | Finds | What a user notices |
|---|---|---|---|
| [OPS001](runbooks/OPS001-stuck-sync-job.md) | CRITICAL | A sync run that never finished | New calendar events silently stop appearing |
| [OPS002](runbooks/OPS002-stale-sync-job.md) | HIGH | No successful sync recently enough | Availability drifts further from truth hourly |
| [OPS003](runbooks/OPS003-facility-sla.md) | MEDIUM | Facility tickets past SLA | A broken room stays broken, in silence |

### Database health (5)

| ID | Severity | Finds | What a user notices |
|---|---|---|---|
| [DBH001](runbooks/DBH001-connection-saturation.md) | HIGH | Connection slots near exhausted | At 100%, every request fails at once |
| [DBH002](runbooks/DBH002-long-running-queries.md) | HIGH | Queries running far too long | Pages hang |
| [DBH003](runbooks/DBH003-blocked-sessions.md) | CRITICAL | Sessions blocked on a lock | Writes stall until something times out |
| [DBH004](runbooks/DBH004-autovacuum-lag.md) | MEDIUM | Dead rows outpacing autovacuum | Gradual slowdown; eventually wraparound |
| [DBH005](runbooks/DBH005-idle-in-transaction.md) | HIGH | A session idle inside a transaction | Holds locks; stops autovacuum everywhere |

Severity is assigned by **user-visible consequence**, not by how interesting the bug is. A ghost
badge is CRITICAL because it is a physical security hole no software alarm will ever raise. An
over-capacity booking is MEDIUM because the worst case is an awkward meeting.

Several checks **escalate on volume**, because a difference in scale is a difference in kind:
three orphaned bookings is a bad delete, five hundred is a failed migration, and the runbook's
first step is different.

---

## Every finding has a next step

Fifteen checks, fifteen runbooks. Each one covers **Confirm**, **Fix**, **Prevent** and
**Escalate**, with the SQL to run at each stage. A finding without a next step is only half an
answer, and a build-time test fails if any check's runbook is missing or is missing a section.

The runbooks are where the real operational content is. A few examples of what is in them:

- **DI001** shows how to add the missing foreign key to a 10-million-row table with `NOT VALID`
  and a later `VALIDATE`, so the constraint takes a brief lock instead of scanning everything.
- **DI002** gives the `EXCLUDE USING gist` constraint that makes double-booking *impossible*
  rather than merely detectable — with `'[)'` bounds, so a meeting ending exactly when the next
  begins is still allowed.
- **DI003** insists the badge is deactivated in the access-control system **first**, because
  editing the database row without it leaves the badge working and hides the problem from the
  check.
- **DBH004** explains why vacuuming while a long transaction is open accomplishes nothing, and
  why `VACUUM FULL` is a maintenance window rather than an incident response.
- **DBH005** gives the one setting — `idle_in_transaction_session_timeout` — that removes an
  entire class of problem, and notes the toolkit applies it to its own sessions.

---

## Grounded context for an assistant

`--format prompt` emits every finding, its sample rows, and the **full text of the relevant
runbook**, followed by a structured instruction to reason only from that material.

```bash
java -jar triage.jar --format prompt | pbcopy
```

The slow part of triage is rarely the query — it is reassembling context at 2am. And there is one
question the tool structurally *cannot* answer: the fifteen checks are independent by design, which
is what makes them individually trustworthy, and it is also why nothing in the tool knows that a
stuck calendar sync (OPS001) is usually the *cause* of the duplicate events it finds next (DI007).
So the prompt asks for exactly that, along with a summary, the first three steps taken from the
runbooks, and what would confirm or rule each hypothesis out.

**The tool never calls a model.** A diagnostic pointed at production must not acquire an outbound
dependency; findings contain real row data whose destination is the operator's compliance decision,
not a default; exit codes 0/1/2 are a contract that nothing non-deterministic belongs upstream of;
and a pure function of the report can be tested with no credentials and no network. The grounding
is the hard part and the tool does that — inference stays an explicit choice made by piping the
output somewhere.

It is evaluated on the one property that is deterministic: **context completeness.** Not "does the
model answer well", but *for every question the prompt asks, is the material needed to answer it
present?* `PromptCompletenessTest` asserts that question by question — 11 tests. Full reasoning,
including where generative AI would be a mistake here, in [docs/genai.md](docs/genai.md).

## Safe against production, in four layers

"Safe to run against production at any time" is the objective that would do real damage if it were
merely believed. It does not rest on a single mechanism.

| Layer | What it stops |
|---|---|
| **Static scan at load time** | A check containing `INSERT`/`UPDATE`/`DELETE`/DDL, a second statement, or a function like `pg_terminate_backend`. A test runs this over all 15 shipped queries, so a check that could write cannot be released. |
| **Read-only session** | Any write on this connection, whatever the SQL says. |
| **Verified, not assumed** | The tool asks the server `SHOW transaction_read_only` and **closes the connection** if the answer is not `on`. A misconfigured driver, a pooler that rewrote the session, or a future edit to this class all fail closed. |
| **Statement timeout per check** | A check that would hold resources on a struggling server. `SET LOCAL`, so it cannot leak into the next check. |

Plus: `lock_timeout` of one second, so a diagnostic never joins the lock queue it is reporting on;
short transactions always rolled back, so the tool reporting DBH005 can never be the cause of it;
a named `application_name` so a DBA can see what the connection is; and **no `--password` option
at all**, because a password on the command line is visible to every process on the host.

`SafetyIT` tries to break each layer — attempting writes, handing the guard a genuinely writable
session, running a query built to be slow — and a checksum test proves a full run changes nothing.

---

## Exit codes

| Code | Meaning |
|---|---|
| **0** | Every selected check ran and found nothing at or above `--fail-on` |
| **1** | Every selected check ran; at least one found something |
| **2** | The run was incomplete: bad arguments, no connection, or a check that failed or timed out |

A check that **could not run outranks every finding**, so one broken check gives exit 2 even
alongside a CRITICAL finding. "I looked and found problems" and "I could not look" must never be
the same signal, or a broken deployment of the toolkit reads as a healthy database. The JSON obeys
the same rule: passed checks are listed explicitly, so "DI003 ran and found nothing" is
distinguishable from "DI003 is missing".

---

## What changed since last time? (`--compare`, `--history-dir`)

A single run answers "is anything wrong?". On call the question is "what is wrong *now that
wasn't at 09:00*?" -- a finding that has been open and ticketed for a week is noise; the one that
appeared since the last run is the page.

```bash
# keep every run; each one is compared with the newest report already in the directory
0 * * * * /usr/bin/java -jar /opt/triage.jar --history-dir /var/lib/triage --fail-on-regression --format json

# or compare two runs by hand
java -jar triage.jar --compare /var/lib/triage/2026-09-18T09-00-00Z.json
```

The text report gains a `SINCE` section; the JSON gains a `comparison` block. Every check is
classified by id: **NEW** (finding now, passed before), **RESOLVED**, **WORSENED** / **IMPROVED**
(finding both times, count moved -- with the delta), **UNCHANGED**, **BROKE** (ran last time, could
not run now) and **RECOVERED**. Checks that passed both times are silent. Checks present in only one
run are **NOT_COMPARED**, so narrowing `--group` between runs can never read as "everything resolved".

`--fail-on-regression` changes only the *1-vs-0* decision: exit 1 for NEW, WORSENED or BROKE, exit 0
for findings that were already open. Exit 2 still means the run itself is not trustworthy. Severity
is deliberately not compared -- it is derived from the count and the thresholds, so comparing it would
report a threshold change as a change in the database.

---

## Findings as metrics (`--format prometheus`)

```bash
# node_exporter textfile collector: every check becomes a time series
*/15 * * * * /usr/bin/java -jar /opt/triage.jar --format prometheus > /var/lib/node_exporter/textfile/triage.prom.$$ \
             && mv /var/lib/node_exporter/textfile/triage.prom.$$ /var/lib/node_exporter/textfile/triage.prom
```

`triage_check_matches{check="DI002",severity="CRITICAL"}` is a graph and a threshold instead of a
log line somebody greps; `triage_check_ran == 0` alerts when a check *stops running*, which a
missing series would never do. Passed checks are exported with 0 matches and `ran="1"` for the same
reason. With `--compare`, `triage_change{kind="NEW"}` and `triage_regressions` come along. Sample
rows are never exported — metrics are long-lived and low-cardinality, findings are neither.

---

## Fitting into automation

JSON on stdout, meaningful exit codes, no interactive prompts.

```bash
# cron, alerting only on the serious things
0 * * * * /usr/bin/java -jar /opt/triage.jar --fail-on HIGH --format json >> /var/log/triage.jsonl 2>&1

# CI gate
java -jar triage.jar --group data || exit 1

# pull out what an alert needs
java -jar triage.jar --format json | jq -r '.findings[] | "\(.severity) \(.checkId) \(.matchCount) \(.runbook)"'
```

Docker:

```bash
docker build -t triage .
docker run --rm -e PGPASSWORD="$PGPASSWORD" triage \
  --host db.internal --database bookings --user readonly --format json
```

The image runs as a non-root user, carries no compiler or source, and ships the runbooks alongside
the jar so a finding's runbook path resolves to a file that is actually there.

---

## Running it in production

Validated artifacts in [`deploy/`](deploy), for four environments:

| | | Validated in CI by |
|---|---|---|
| [`triage.service`](deploy/triage.service) + [`triage.timer`](deploy/triage.timer) | systemd, preferred on Linux | `systemd-analyze verify` |
| [`crontab.example`](deploy/crontab.example) | hosts without systemd | — |
| [`kubernetes-cronjob.yaml`](deploy/kubernetes-cronjob.yaml) | Kubernetes CronJob | `kubeconform -strict` |
| [`aws/main.tf`](deploy/aws/main.tf) | EventBridge Scheduler → ECS Fargate | `terraform validate`, `terraform fmt -check` |

The detail that matters in all four is the same: **exit 1 means findings, which is a successful
run.** A scheduler that treats it as failure will retry forever against a database that has a real
problem, and the alerts get muted within a week. Hence `SuccessExitStatus=0 1`, `backoffLimit: 0`,
and `maximum_retry_attempts = 0`.

The systemd timer uses `Persistent=true` so a run missed while the host was down is caught up, and
`RandomizedDelaySec=300` so a fleet does not stampede the database on the hour. The Kubernetes and
Fargate CPU requests are `1`, taken from the single-CPU benchmark rather than guessed.

[docs/operating.md](docs/operating.md) covers all of it, including the read-only database role to
connect as — and the `GRANT pg_monitor` that people miss, without which the five database-health
checks see only their own session, report nothing, and look perfectly healthy.

## Testing

**176 tests: 132 unit, 44 integration against a real PostgreSQL. 87.2% line coverage.**

The two headline criteria are asserted directly, in-process and again through the real jar in CI:

- **On clean data, all 15 checks pass and the tool exits 0.**
- **After the six scenarios are injected, exactly the six matching checks report — three findings
  each — and the tool exits 1.**

The counts are asserted exactly, not as "at least one", because an over-broad check that also
matched clean rows would still pass a loose assertion while being wrong. Each scenario is also
tested **alone**, proving the six are independent rather than only correct as a set.

**Every one of the 15 checks is proven to actually fire.** The six scenarios cover DI001-DI004,
OPS001 and OPS003; `CheckFiresIT` creates the real condition for the other nine and asserts each
one reports it. That includes genuinely saturating connections, running a query that is still
executing, blocking one session behind another's lock, accumulating dead tuples with autovacuum
disabled, and abandoning a session inside an open transaction -- the five database-health checks
read `pg_stat_activity` and `pg_blocking_pids()`, so there is no other way to test them.

This matters more than it sounds. A check with a typo in its `WHERE` clause returns zero rows
forever and looks perfectly healthy, and until these tests existed nine checks were only ever
asserted to return nothing. Verified by sabotage: making DI006 match nothing now fails the build
with `DI006 should have reported a finding, but was PASS`. A test guards the guard, so a
sixteenth check added without a firing test fails by name.

Coverage is measured across **both** test phases and merged. A single Jacoco agent instruments
only the unit-test JVM, which reported 47.8% for this project and made `ConnectionFactory` and
`TriageRunner` look untested when the integration tests exercise them heavily; the merged figure
is 88.7%. The two classes still below that are `Main`, which is one `System.exit` line and is
deliberately untestable in-process, and the CLI's connect-and-run path, which is covered by
`CliIT` through a real subprocess that Jacoco cannot instrument.

Integration tests use a real server rather than a mock, because five of the fifteen checks read
`pg_stat_activity`, `pg_stat_user_tables` and `pg_blocking_pids()` — there is nothing meaningful
to assert about those against a fake. The server comes from Docker Compose locally and a service
container in CI. A missing database **fails with instructions rather than skipping**: a skipped
integration test is a green build that proved nothing, which is the exact failure the tool's own
exit code 2 exists to prevent.

---

**The documentation is tested too.** This repo is roughly half prose, and prose drifts silently
because nothing executes it. `scripts/verify-docs.py` runs as a fourth CI criterion and checks
that the counts on disk match what the README claims, that every check id is documented, that
every relative link resolves, that the benchmark figures quoted here match the committed result
JSON, that the stated test counts and coverage match the actual reports, and that the sample run
above still matches what the tool prints. A claim that stops being true fails the build.

## The six failure scenarios

| Scenario | Must be caught by | Story |
|---|---|---|
| `01-orphaned-bookings` | DI001 | A cleanup script deleted rooms without checking for bookings |
| `02-double-booked-room` | DI002 | A race in the booking API: both requests read "free" before either wrote |
| `03-ghost-badge` | DI003 | The nightly HR feed failed partway through |
| `04-over-capacity` | DI004 | A bulk import carried attendee counts across a room refit |
| `05-stuck-sync-job` | OPS001 | Three sync runs died in three different ways |
| `06-facility-sla-breach` | OPS003 | The facilities queue was not worked over a long weekend |

Each injects exactly three problems and is independent of the others — the SQL comments explain
the care that took. Scenario 04 skips the first 20 rows of the window specifically so it cannot
land on the bookings scenario 02 inserted; without that, the two would overlap and neither would
prove what it claims.

Undo any of them with `./scripts/seed.sh`.

---

## Layout

```
src/main/java/com/abheenash/triage/
    cli/          picocli command, exit codes
    core/         check catalogue, runner, report, thresholds, SQL safety scanner
    db/           connection factory with the read-only guarantees
    report/       text and JSON reporters
src/main/resources/checks/sql/     the 15 queries, one .sql file each
db/               schema.sql, indexes.sql, drop-indexes.sql, generate.sql
scenarios/        the six injectable failures
runbooks/         15 runbooks: confirm, fix, prevent, escalate
scripts/          sandbox-up, seed, inject, triage, test, benchmark
deploy/           systemd units, cron, Kubernetes CronJob, AWS Terraform
docs/             architecture, benchmark, operating, genai, schema-notes
benchmark/results/  raw measurements as JSON
```

The check SQL lives in `.sql` files rather than Java strings on purpose: someone on call has to be
able to read it, review it as SQL, and paste it into psql. `--show-sql DI002` prints the exact
text with thresholds already substituted.

---

## Things worth knowing

**The sample schema is missing three constraints on purpose** — no foreign key on
`bookings.room_id`, no `CHECK (ends_at > starts_at)`, no unique index on `external_event_id`. They
are the three that real high-write tables most often lack, their absence is what lets DI001, DI005
and DI007 have anything to find, and all three runbooks prescribe adding them as the permanent
fix. [The full reasoning](docs/schema-notes.md).

**The data generator uses no randomness at all.** Every value is a function of the row ordinal, so
the same input always produces byte-identical data. Bookings are laid on a deterministic
(room, time-slot) grid rather than sampled, which means two confirmed bookings in one room
*cannot* overlap — DI002 is clean by construction, not by luck. Random timestamps would collide
constantly at 10 million rows and the clean baseline would be worthless.

**Tuning mattered more on less hardware, not less.** Pinning the database to a single CPU costs
the tuned run only 15% (1,069 ms to 1,228 ms) but makes the untuned run three times slower
(2,259 ms to 6,911 ms). Parallel workers were largely rescuing the *untuned* queries — a
sequential scan of 10 million rows splits across cores beautifully. The tuned queries do less
work and are not parallel anyway: DI002 takes 524 ms on one core against 526 ms on eight.

**The biggest performance win was not an index.** DI002 was originally a self-join, and adding the
index that "should" have helped made the whole run *slower* — it let the planner replace one bulk
hash join with 910,750 individual index probes. Rewriting the check as a window function over a
sorted pass took it from 2,147 ms to 526 ms. Then measuring index usage per check found four
indexes with **zero scans** totalling 410 MB, which were removed with no loss of speed — and one
index that also showed zero scans and was kept, because it costs nothing on a healthy database and
saves 224 ms exactly when the check has something to report. [The whole
story](docs/benchmark.md).

---

## Not in version 1

- **Automatic fixes.** The tool reports; people fix data by following the runbooks.
- **Other databases.** PostgreSQL 12+ only.
- **A web dashboard.** Text or JSON.
- **Built-in scheduling or notifications.** Run it from cron, EventBridge, or CI.
- **Real data.** Generated only.

Configurable thresholds were scoped for version 2 and landed early — the 7-day booking window
already needed a substitution mechanism, so exposing the other seven knobs cost one CLI option
(`--threshold`, `--list-thresholds`). Substituted values must parse as non-negative numbers and
the name must already exist, so `--threshold 'booking_window_days=7; DROP TABLE bookings'` fails
with "must be a number". There is a test for that.

Still ahead: CloudWatch or Prometheus metrics, SNS/PagerDuty alerting, EventBridge + Lambda or a
Kubernetes CronJob, and MySQL support.

---

## Requirements

PostgreSQL 12 or later, Java 17 or later. Docker for the sandbox, the benchmark, and the
integration tests.

The 2-second target at 10 million rows is met on an 8-core M4 (1,069 ms) and with the database
limited to one CPU (1,228 ms), so it does not depend on a fast machine.

Booking checks look only at bookings from 7 days ago onward — older history cannot be acted on,
and the bound is what keeps the tool fast at 10 million rows. Change it with
`--threshold booking_window_days=N`.

## License

MIT. See [LICENSE](LICENSE).
