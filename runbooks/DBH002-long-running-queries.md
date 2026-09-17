# DBH002 -- Query running far longer than expected

**Severity** HIGH, escalating to CRITICAL at 5 queries
**Group** Database health
**What the user sees** Pages hang. One runaway query can hold a connection the whole site needs.

## What this means

A client query has been executing for more than 60 seconds. In an application whose slowest
intended query is a few hundred milliseconds, a minute means something has gone wrong: a missing
index after a data-volume change, a plan that flipped, an accidental cross join, or a query
waiting on a lock.

`wait_event_type` settles the most important question in one glance:

| `wait_event_type` | Meaning | Where to go |
|---|---|---|
| `NULL` | Actually executing. It is doing work, just too much of it | Read the plan |
| `Lock` | Blocked by another session | DBH003, which names the blocker |
| `IO` | Waiting on disk. Often a scan too large for cache | Read the plan |
| `Client` | Waiting for the client to read results | The application is the slow part |

The toolkit excludes its own session and all background workers, so autovacuum's long run does
not appear here.

## Confirm

Get the full query text -- the finding truncates it to keep the table readable:

```sql
SELECT pid, usename, application_name, client_addr,
       round(EXTRACT(epoch FROM (now() - query_start)), 1) AS running_seconds,
       wait_event_type, wait_event, query
FROM pg_stat_activity
WHERE pid = :pid;
```

If it is waiting on a lock, find the blocker before doing anything else:

```sql
SELECT pg_blocking_pids(:pid);
```

Anything returned there means this is DBH003's problem and killing this query solves nothing --
it will just be replaced by the next victim of the same blocker.

If it is genuinely executing, get the plan **without running it**:

```sql
EXPLAIN (COSTS, VERBOSE)
SELECT ...;   -- paste the query text from above
```

Never `EXPLAIN ANALYZE` a query you suspect of being the problem: that runs it again, which on a
struggling server makes things worse.

Check whether it is a one-off or a pattern. If `pg_stat_statements` is available:

```sql
SELECT calls, round(mean_exec_time) AS mean_ms, round(max_exec_time) AS max_ms,
       left(query, 120) AS query
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 20;
```

## Fix

**Decide whether to cancel.** A long analytical query that somebody is waiting on may be fine;
the same query blocking a deploy is not. Prefer cancel over terminate:

```sql
-- Cancels the query, keeps the session and its transaction.
SELECT pg_cancel_backend(:pid);

-- Only if cancel does not work: ends the whole session and rolls back its transaction.
SELECT pg_terminate_backend(:pid);
```

Cancel first, wait a few seconds, then escalate to terminate. A query stuck in a tight C loop
sometimes will not notice a cancel, but most do.

**Then fix the cause**, which is usually one of:

- *Missing index.* A plan showing a sequential scan on a large table where a filter is selective.
  Add the index `CONCURRENTLY`.
- *Stale statistics.* The planner is choosing badly because its row estimates are wrong.
  `ANALYZE <table>;` and re-check the plan. DBH004 often reports at the same time.
- *Unbounded query.* A report with no date filter that got slower as data grew -- the same class
  of problem the 7-day booking window in this toolkit exists to avoid.
- *Data skew.* A plan that is fine for most inputs and terrible for one value.

## Prevent

- Set a `statement_timeout` at the role level for application users, so a runaway query is
  cancelled automatically rather than found by a check. Interactive and reporting roles can have
  a longer one.
- Enable `pg_stat_statements`. Without it, "is this query normally slow?" cannot be answered, and
  that is the first question every time.
- Set `log_min_duration_statement` so slow queries are recorded even when nobody is looking.
- Keep the application's queries bounded by time or by key, so their cost does not grow with the
  table.

## Escalate

Escalate to the database owner if:

- five or more queries are simultaneously long-running, which means the server is saturated
  rather than one query being bad;
- cancelling has no effect;
- `wait_event_type` is `Lock`, in which case DBH003 is the real incident;
- the same query text reappears in run after run -- that is a code fix, not an operational one.
