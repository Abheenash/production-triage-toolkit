# DBH001 -- Connection slots close to exhausted

**Severity** HIGH
**Group** Database health
**What the user sees** At 100% every new request fails at once, with no warning beforehand.

## What this means

Client connections are at or above 80% of the usable slots (`max_connections` minus
`superuser_reserved_connections`). This is a leading indicator with a cliff at the end: the
database performs identically at 79% and at 99%, and then at 100% every new connection is refused
and the application is down. There is no gradual degradation to notice.

`idle` versus `idle in transaction` in the finding is the key split. Many `idle` connections is a
pool sized too large, which is wasteful but stable. Many `idle in transaction` is a leak, which
gets worse on its own -- and DBH005 covers that specifically.

## Confirm

Find out who is holding the connections:

```sql
SELECT usename, application_name, client_addr, state, count(*)
FROM pg_stat_activity
WHERE backend_type = 'client backend'
GROUP BY 1, 2, 3, 4
ORDER BY count DESC;
```

One application name dominating means a pool misconfigured or leaking. Spread evenly across
several means you have simply outgrown `max_connections`.

```sql
-- How much room is actually left.
SELECT current_setting('max_connections')::int AS max_connections,
       current_setting('superuser_reserved_connections')::int AS reserved,
       count(*) AS in_use
FROM pg_stat_activity WHERE backend_type = 'client backend';
```

Check how long the idle ones have been idle. Long-idle connections are a pool holding more than
it needs:

```sql
SELECT application_name, state,
       count(*),
       round(max(EXTRACT(epoch FROM (now() - state_change)))) AS oldest_idle_seconds
FROM pg_stat_activity
WHERE backend_type = 'client backend' AND state LIKE 'idle%'
GROUP BY 1, 2 ORDER BY count DESC;
```

## Fix

**Immediately, if you are near the cliff.** Free slots by ending sessions that are doing nothing
inside a transaction. These are the safest to end because they hold locks and do no work:

```sql
-- Look first.
SELECT pid, usename, application_name,
       round(EXTRACT(epoch FROM (now() - state_change))) AS idle_seconds
FROM pg_stat_activity
WHERE state = 'idle in transaction'
  AND state_change < now() - interval '5 minutes'
ORDER BY state_change;

-- Then end them, one at a time, checking each pid against the list above.
SELECT pg_terminate_backend(:pid);
```

Terminating a backend rolls its transaction back. That is safe for an idle-in-transaction session
by definition -- it was not doing anything -- but it is not safe as a blanket action, so never run
this as a bulk `SELECT pg_terminate_backend(pid) FROM pg_stat_activity ...`.

The triage toolkit will never do this for you. It is read-only, and terminating backends is a
decision a person makes.

**Then fix the cause.** Almost always the pool, not the server:

- Total pool size across all application instances must be below `max_connections`. Instances
  multiply: 10 instances with a pool of 20 is 200 connections, whatever the config file for one
  instance says.
- A connection pool in front of the database (PgBouncer in transaction mode) lets many
  application connections share few server ones, and is the right answer when instance count is
  the thing growing.
- Raising `max_connections` is the last resort. Each connection costs memory, and it requires a
  restart.

## Prevent

- Alert at 80%, not at 95%. The whole value of this check is the warning before the cliff.
- Size pools deliberately: a small pool with a queue usually beats a large pool with contention,
  since the database cannot execute more concurrent work than it has cores for anyway.
- Set `idle_in_transaction_session_timeout` at the server or role level so leaked transactions
  are cleaned up automatically rather than accumulating until this check fires.
- Watch connection count as a trend. It usually creeps up with deployments, which makes it
  predictable and therefore preventable.

## Escalate

Escalate to the database owner and the application on-call if:

- usage is above 95%, which is minutes away from refusing connections;
- the count is rising steadily between runs rather than sitting flat -- something is leaking and
  it will reach 100%;
- one application name holds the majority of connections, which needs that application's owner;
- DBH005 is also reporting, since idle-in-transaction sessions are both a cause of this and a
  separate problem in their own right.
