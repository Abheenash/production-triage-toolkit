# DBH003 -- Sessions blocked waiting on a lock

**Severity** CRITICAL
**Group** Database health
**What the user sees** Writes stall behind one holder and the queue grows until something times out.

## What this means

One or more sessions are waiting on a lock another session holds. Lock waits are normal and
usually last milliseconds; a wait long enough to appear here is not normal.

This is CRITICAL because it compounds. Every session that arrives behind the blocker joins the
queue, so connection count climbs (DBH001) while throughput falls. A single uncommitted
transaction can stop writes across the whole application.

The finding names both sides, which is the point -- it turns "the site is slow" into a specific
pid to act on. `blocking_state` is the field to read first:

| `blocking_state` | What is happening |
|---|---|
| `idle in transaction` | The blocker is doing **nothing** and holding locks. This is the common case, and DBH005 covers it |
| `active` | The blocker is doing real work. It may simply need to finish |
| `idle` | Unusual. Often a session holding an advisory lock, or a prepared transaction |

## Confirm

See the whole wait graph, not just one pair. Chains matter: killing the middle of a chain
achieves nothing.

```sql
SELECT blocked.pid          AS blocked_pid,
       blocked.application_name AS blocked_app,
       round(EXTRACT(epoch FROM (now() - blocked.query_start))) AS blocked_seconds,
       blocking.pid         AS blocking_pid,
       blocking.state       AS blocking_state,
       round(EXTRACT(epoch FROM (now() - blocking.state_change))) AS blocking_state_seconds,
       left(regexp_replace(blocked.query,  '\s+',' ','g'), 100) AS blocked_query,
       left(regexp_replace(blocking.query, '\s+',' ','g'), 100) AS blocking_query
FROM pg_stat_activity blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS bp(pid)
JOIN pg_stat_activity blocking ON blocking.pid = bp.pid
ORDER BY blocked_seconds DESC;
```

Find the root blocker -- the session that is blocked by nobody:

```sql
SELECT pid, state, application_name, xact_start,
       round(EXTRACT(epoch FROM (now() - xact_start))) AS transaction_seconds,
       left(regexp_replace(query, '\s+', ' ', 'g'), 200) AS query
FROM pg_stat_activity
WHERE cardinality(pg_blocking_pids(pid)) = 0
  AND pid IN (SELECT unnest(pg_blocking_pids(pid)) FROM pg_stat_activity);
```

That is the only session worth acting on. Everything else is downstream of it.

What lock, and on what:

```sql
SELECT locktype, relation::regclass AS table, mode, granted
FROM pg_locks WHERE pid = :blocking_pid;
```

An ungranted `AccessExclusiveLock` usually means a schema change -- a migration -- is queued
behind readers and is now blocking everything that arrives after it.

## Fix

Act on the **root blocker**, never on the victims.

1. **Blocker is `idle in transaction`.** It is holding locks and doing nothing. Terminating it
   rolls back a transaction that was not progressing anyway:

```sql
SELECT pg_terminate_backend(:root_blocking_pid);
```

2. **Blocker is `active` and doing legitimate work.** Let it finish if it is close. If it is a
   runaway, DBH002 applies: cancel it first, terminate only if cancel fails.

3. **Blocker is a migration.** Decide deliberately. Cancelling a half-applied schema change can
   be worse than the outage. Talk to whoever is running the deploy before touching it.

After clearing, confirm the queue drained:

```sql
SELECT count(*) AS still_blocked
FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;
```

The toolkit itself can never do any of this: it is read-only, and it sets `lock_timeout` to one
second so its own checks never join the queue they are reporting on.

## Prevent

- Set `idle_in_transaction_session_timeout` at the server or role level. This one setting removes
  the most common cause of this finding entirely.
- Keep transactions short, and never do network I/O inside one. A transaction opened before an
  HTTP call holds locks for the duration of that call.
- Run schema migrations with a `lock_timeout` set, so a migration that cannot get its lock fails
  fast instead of queueing the entire application behind it:

```sql
SET lock_timeout = '3s';
ALTER TABLE ...;
```

- Prefer `CREATE INDEX CONCURRENTLY` and `ADD CONSTRAINT ... NOT VALID` for changes to large
  tables, both of which avoid long exclusive locks.

## Escalate

Escalate immediately to the database owner and the application on-call if:

- any session has been blocked for more than 60 seconds -- user-facing requests are already
  failing;
- the number of blocked sessions is growing between runs;
- the root blocker is a migration or deploy, which is a decision above the on-call's pay grade;
- DBH001 is also reporting, since the queue is consuming connections and the two together end in
  a full outage.
