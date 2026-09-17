# OPS002 -- No successful sync run recently enough

**Severity** HIGH
**Group** Operations
**What the user sees** Room availability drifts further from the truth with every hour that passes.

## What this means

No run of this job has succeeded within the staleness window (6 hours by default, against a job
that runs every 30 minutes). OPS001 catches a run wedged in `running`; this catches every other
shape of silence:

- the scheduler stopped firing at all;
- every run is failing;
- the job was disabled during an incident and never re-enabled;
- the job was renamed or removed and nothing noticed.

The check reports one row per `job_name`, so a job that stopped existing still reports rather than
vanishing from the output. A job that disappears from monitoring is the failure mode that
monitoring is supposed to prevent.

`failed_runs_24h` and `runs_in_progress` separate the causes immediately: failures mean it is
trying and losing; zero of both means nothing is even attempting.

## Confirm

Look at the recent history:

```sql
SELECT run_id, status, started_at, finished_at, rows_processed, error_message
FROM sync_job_runs
WHERE job_name = :job_name
ORDER BY started_at DESC
LIMIT 30;
```

Read the shape of it:

| What you see | Cause |
|---|---|
| Nothing at all since a point in time | The scheduler is not firing. Check the scheduler, not the job. |
| A run in `running` | This is really OPS001. Go there first. |
| Repeated `failed` with one message | The job is broken, or its upstream is. Read `error_message`. |
| Successes, but too slow | The interval was changed, or runs are taking longer than the gap. |

```sql
-- Are the gaps between runs what they should be?
SELECT started_at,
       started_at - lag(started_at) OVER (ORDER BY started_at) AS gap
FROM sync_job_runs
WHERE job_name = :job_name AND started_at > now() - interval '24 hours'
ORDER BY started_at DESC;
```

Then measure how wrong the data now is, which is what anyone asking about impact will want:

```sql
SELECT count(*) AS calendar_bookings_last_24h,
       max(created_at) AS most_recent_sync_write
FROM bookings
WHERE source = 'calendar_sync' AND created_at > now() - interval '24 hours';
```

## Fix

The fix depends on the cause, and there is no generic one:

1. **Scheduler not firing.** Fix the schedule, then trigger a run by hand to catch up. Check
   whether other jobs on the same scheduler are also silent -- if so, the scheduler is the
   incident and this job is a symptom.
2. **Runs failing.** Read `error_message`. An upstream calendar API returning 5xx is not something
   to fix here; confirm the upstream status and, if it is down, note it and wait rather than
   burning retries.
3. **Job disabled and forgotten.** Re-enable it and find out why the re-enable was missed. This
   is usually a gap in an incident checklist rather than a technical fault.
4. **Runs too slow for the interval.** Either lengthen the interval or make the run faster.
   Runs that overlap their own schedule eventually produce the stuck rows that OPS001 finds.

In every case, trigger a run and confirm it succeeds before closing:

```sql
SELECT run_id, status, started_at, finished_at, rows_processed
FROM sync_job_runs
WHERE job_name = :job_name
ORDER BY started_at DESC LIMIT 1;
```

Expect `rows_processed` to be well above normal on the catch-up run. If it is normal, the run is
not actually catching up on the backlog and step 1 is not finished.

## Prevent

- **Alert on absence, not just on failure.** A job that stops running produces no failure events
  at all, which is exactly why this check is written around "when did it last succeed" rather
  than "did anything fail".
- Set the staleness threshold from the schedule, with room for one miss: a job every 30 minutes
  is reasonably stale at 2 hours, definitely stale at 6. Adjust with
  `--threshold stale_job_hours=N`.
- Record every run, including the ones that fail immediately. A run that is not recorded cannot
  be missed.
- Keep disabled jobs visible. If disabling means deleting the schedule, nothing can report on it.

## Escalate

Escalate to the application on-call if:

- there has been no success for more than one business day -- availability data is now
  substantially wrong and users are making decisions on it;
- `failed_runs_24h` is high and `error_message` points at an upstream service, which needs that
  service's owner rather than a database change;
- multiple jobs are stale at once, which is a scheduler-level incident;
- the job has no rows at all, meaning it has not run since before the retention window and may
  have been removed entirely.
