# OPS001 -- Sync job started and never finished

**Severity** CRITICAL
**Group** Operations
**What the user sees** New calendar events stop appearing, silently, with no error anywhere.

## What this means

A row in `sync_job_runs` has `status = 'running'` and has been that way longer than the job should
ever take. The process that owned it is almost certainly gone -- OOM-killed, deployed over, or
disconnected -- and died without updating its row.

This is CRITICAL because of what happens next. The scheduler will not start a run while one is
already marked running, so a single wedged row stops *every* subsequent sync. Nothing errors.
The data just gets quietly staler, and the first complaint arrives hours later.

`stuck_reason` tells you which of two situations you have, and they need opposite responses:

| `stuck_reason` | What it means | What to do |
|---|---|---|
| `never sent a heartbeat` | Died almost immediately, probably at startup | Check startup errors; the row is safe to clear |
| `heartbeat stopped advancing` | Died mid-run | Find out where it got to before clearing |
| `alive but running past its expected duration` | **Still running.** Wedged on something upstream | Do NOT clear the row. Find what it is waiting on |

The third case is the one that catches people out: the process is alive and dutifully sending
heartbeats, it is simply never going to finish. Clearing that row lets a second run start
alongside it, and now two runs are writing the same events -- which is how DI007 duplicates get
made.

## Confirm

Look at the run and what normal looks like:

```sql
SELECT run_id, status, started_at, heartbeat_at, rows_processed, error_message,
       round(EXTRACT(epoch FROM (now() - started_at)) / 60.0, 1) AS running_minutes
FROM sync_job_runs
WHERE job_name = :job_name
ORDER BY started_at DESC
LIMIT 20;
```

```sql
-- What a healthy run costs, for comparison.
SELECT count(*) AS runs,
       round(avg(EXTRACT(epoch FROM (finished_at - started_at))), 1) AS avg_seconds,
       round(max(EXTRACT(epoch FROM (finished_at - started_at))), 1) AS worst_seconds
FROM sync_job_runs
WHERE job_name = :job_name AND status = 'succeeded'
  AND started_at > now() - interval '7 days';
```

If the worst healthy run is 45 seconds and this one is at 95 minutes, the process is gone.

Then confirm whether it is genuinely dead. Check the scheduler or orchestrator for the process,
and check whether it still holds a database session:

```sql
SELECT pid, state, application_name, query_start, wait_event_type, wait_event,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
FROM pg_stat_activity
WHERE application_name ILIKE '%sync%';
```

A live session that is waiting on a lock is case three, and DBH003 will be reporting it too.

## Fix

**Case 1 and 2 -- the process is gone.** Mark the run failed so the scheduler is unblocked:

```sql
BEGIN;
UPDATE sync_job_runs
   SET status = 'failed',
       finished_at = coalesce(heartbeat_at, started_at),
       error_message = 'marked failed by on-call: process gone, no heartbeat since '
                       || coalesce(heartbeat_at::text, 'start')
 WHERE run_id = :run_id
   AND status = 'running';
-- Expect exactly 1 row. Then COMMIT.
COMMIT;
```

Then start a run manually and watch it, rather than waiting for the schedule. Because the sync is
incremental, the next run should pick up what the dead one missed -- confirm that by checking
`rows_processed` on the new run is larger than usual.

**Case 3 -- it is alive and wedged.** Do not touch the row. Find what it is blocked on (DBH003
names the blocker), clear that, and let the run finish on its own. Only if it cannot be unblocked
should you stop the process first, and only then mark the row failed.

After any of these, run `triage --check DI007` -- an interrupted sync and its retry are exactly
how duplicate events appear.

## Prevent

- **Give the job a watchdog.** Anything in `running` past a known ceiling should be marked failed
  automatically, by the scheduler, not by a person reading this page.
- **Heartbeat on a timer, not per batch.** A heartbeat that only advances between batches looks
  dead during one long batch, which produces false findings and teaches people to ignore this
  check.
- **Make the scheduler's guard time-bounded**: skip if a run is in progress *and started within
  the expected window*, so a stale row cannot block the schedule indefinitely.
- **Make the run idempotent** -- see DI007's unique index and `ON CONFLICT` -- so that re-running
  after a failure is always safe and nobody has to reason about it at 2am.

## Escalate

Escalate to the application on-call if:

- `stuck_reason` is `alive but running past its expected duration` and you cannot find what it is
  waiting on;
- clearing the row and re-running produces another stuck run -- the job itself is broken;
- OPS002 is also reporting, which means this has been going on long enough that the data users
  see is now materially wrong;
- DI007 starts reporting afterwards, since the recovery has duplicated events and that needs
  cleaning before anyone trusts room availability.
