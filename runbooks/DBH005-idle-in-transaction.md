# DBH005 -- Session idle inside an open transaction

**Severity** HIGH
**Group** Database health
**What the user sees** Holds locks and stops autovacuum cleaning every table in the database.

## What this means

A session has an open transaction and is executing nothing. It ran a statement, did not commit or
roll back, and went quiet -- for more than 5 minutes by default.

The damage is out of all proportion to how harmless it looks:

- It **holds every lock** the transaction has taken. Anything needing a conflicting lock queues
  behind it, which is DBH003.
- It **pins the oldest snapshot**, so autovacuum cannot remove dead rows *anywhere in the
  database* -- not just in the tables this session touched. That is DBH004.
- It **occupies a connection slot** while doing no work, contributing to DBH001.

One forgotten psql window can therefore cause bloat, lock queues and connection pressure at once.
If DBH003, DBH004 and DBH005 all report together, this is almost always the root cause and the
other two are symptoms.

`state = 'idle in transaction (aborted)'` is the same problem with an extra detail: the
transaction has already errored, so every statement in it will fail until it is rolled back. It
is pure cost with no possible benefit.

## Confirm

Identify what it is and how long it has held the snapshot:

```sql
SELECT pid, usename, application_name, client_addr, state,
       round(EXTRACT(epoch FROM (now() - xact_start)))   AS transaction_seconds,
       round(EXTRACT(epoch FROM (now() - state_change))) AS idle_seconds,
       backend_xid, backend_xmin,
       query AS last_statement
FROM pg_stat_activity
WHERE state LIKE 'idle in transaction%'
ORDER BY xact_start;
```

`query` shows the **last statement executed**, not a running one -- that is what tells you which
code path opened the transaction and walked away.

Check what it is blocking:

```sql
SELECT blocked.pid, blocked.application_name,
       round(EXTRACT(epoch FROM (now() - blocked.query_start))) AS blocked_seconds
FROM pg_stat_activity blocked
WHERE :idle_pid = ANY(pg_blocking_pids(blocked.pid));
```

And what locks it holds:

```sql
SELECT locktype, relation::regclass AS table, mode, granted
FROM pg_locks WHERE pid = :idle_pid AND relation IS NOT NULL;
```

`application_name` and `client_addr` usually identify the owner immediately. A human `psql`
session is a different conversation from an application connection leaking transactions.

## Fix

**Terminating an idle-in-transaction session is safe by definition** -- it is doing nothing, and
the rollback discards work that was never going to be committed. It is still worth a moment's
identification first, in case it is a person mid-way through something deliberate.

```sql
-- Confirm it is still idle right now, not just when the check ran.
SELECT pid, state, application_name,
       round(EXTRACT(epoch FROM (now() - state_change))) AS idle_seconds
FROM pg_stat_activity WHERE pid = :pid;

-- Then end it.
SELECT pg_terminate_backend(:pid);
```

If it is a person, message them instead of terminating. If it is an application, terminate and
then fix the leak, because it will come straight back.

The usual causes, in the order they turn up:

- **Network I/O inside a transaction.** `BEGIN`, then an HTTP call to a slow third party, then
  `COMMIT`. The transaction stays open for the length of that call.
- **An ORM session left open** after a request finished, typically an error path that skips the
  commit or rollback.
- **A pool handing back connections without resetting them**, so a transaction started by one
  request outlives it.
- **A `psql` window** where somebody typed `BEGIN` and went to lunch.

## Prevent

Set the server-side timeout. This one setting removes the whole class of problem, and it is the
single highest-value change on this page:

```sql
-- Per role, which is the safer place to start: application roles only.
ALTER ROLE app_user SET idle_in_transaction_session_timeout = '60s';

-- Or globally, in postgresql.conf.
idle_in_transaction_session_timeout = 60000   -- milliseconds
```

Choose a value above your slowest legitimate transaction and well below the point at which bloat
becomes a problem. A minute suits most web applications.

Also:

- Never do network I/O inside a transaction. Fetch first, then open the transaction, write, and
  commit.
- Make the pool reset connections on return, so a leaked transaction cannot outlive its request.
- Set the same timeout on interactive roles, with a longer value, so a forgotten psql window
  cleans itself up.

The toolkit applies this setting to its own sessions -- each check runs in a short read-only
transaction with `idle_in_transaction_session_timeout` set, so the thing reporting this problem
can never be the cause of it.

## Escalate

Escalate to the application on-call if:

- the session is blocking others -- it is now causing an outage, not just risking one;
- transactions have been open for more than 30 minutes, which puts autovacuum meaningfully behind
  across the whole database;
- the same `application_name` reappears run after run, which is a code leak rather than an
  incident;
- DBH003 or DBH004 are reporting at the same time, in which case fix this first and re-check --
  the other two will often clear on their own.
