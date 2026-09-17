# DBH004 -- Dead rows accumulating faster than autovacuum clears them

**Severity** MEDIUM, escalating to HIGH at 5 tables
**Group** Database health
**What the user sees** Everything gets gradually slower, and eventually risks transaction wraparound.

## What this means

A table has more than 10,000 dead tuples and they make up at least 20% of its rows. In
PostgreSQL an `UPDATE` or `DELETE` does not remove the old row version; it marks it dead, and
autovacuum reclaims it later. When dead rows accumulate faster than autovacuum clears them,
every scan reads rows nobody can see and the table gets steadily slower.

Both conditions must hold, deliberately. A 6-row table with 3 dead rows is 50% dead and entirely
fine; paging someone for it would teach them to ignore this check.

Left long enough this stops being a performance issue. If the oldest transaction id cannot
advance, PostgreSQL will eventually refuse writes to protect against wraparound. That is a
full outage and the recovery is slow.

`last_autovacuum` is the field to read first: recent means autovacuum is running and losing;
old or null means it is not running on this table at all, which are different problems.

## Confirm

See the whole picture, including how stale the statistics are:

```sql
SELECT relname, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       last_autovacuum, last_autoanalyze, autovacuum_count,
       n_mod_since_analyze,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 20;
```

Then find out whether something is blocking autovacuum. These are the three usual culprits, and
all three stop it cleaning **every** table, not just this one:

```sql
-- 1. A long-open transaction pins the oldest snapshot.
SELECT pid, state, application_name,
       round(EXTRACT(epoch FROM (now() - xact_start))) AS transaction_seconds
FROM pg_stat_activity
WHERE xact_start IS NOT NULL
ORDER BY xact_start
LIMIT 5;

-- 2. An abandoned replication slot holds WAL and xmin back.
SELECT slot_name, active, wal_status,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal
FROM pg_replication_slots;

-- 3. A prepared transaction nobody committed.
SELECT gid, prepared, owner FROM pg_prepared_xacts;
```

Check how close to wraparound the database actually is. Under 200 million is the point to start
worrying:

```sql
SELECT datname,
       age(datfrozenxid) AS xid_age,
       2147483648 - age(datfrozenxid) AS xids_remaining
FROM pg_database
ORDER BY xid_age DESC;
```

## Fix

**If something is blocking autovacuum, clear that first.** Vacuuming while a long transaction
pins the snapshot cannot remove the dead rows -- they are still visible to that transaction, so
the vacuum runs, does nothing useful, and you conclude wrongly that vacuum is broken.

- Long transaction: terminate it (see DBH005).
- Inactive replication slot: drop it, but only after confirming the replica is genuinely gone.
  Dropping a slot a live replica needs breaks that replica.

```sql
SELECT pg_drop_replication_slot('slot_name');
```

- Prepared transaction: commit or roll it back.

```sql
ROLLBACK PREPARED 'gid_from_above';
```

**Then vacuum the table.** A plain `VACUUM` is online and safe at any time:

```sql
VACUUM (VERBOSE, ANALYZE) bookings;
```

`VACUUM FULL` rewrites the table and takes an `ACCESS EXCLUSIVE` lock for the whole rewrite --
the table is completely unavailable, potentially for a long time on 10M rows. It is a maintenance
window operation, not an incident response, and it is almost never what you want here.

**If the table is simply write-heavy**, make autovacuum work harder on that table specifically:

```sql
ALTER TABLE bookings SET (
    autovacuum_vacuum_scale_factor = 0.02,   -- default 0.2: trigger at 2% dead, not 20%
    autovacuum_vacuum_cost_limit = 1000      -- let it do more work per round
);
```

The default scale factor is a percentage, which means the bigger the table, the longer autovacuum
waits. On a 10M-row table 20% is 2 million dead rows before it starts. Lowering it for large
tables is standard practice.

## Prevent

- Tune `autovacuum_vacuum_scale_factor` down for your largest, most-updated tables. The global
  default is written for small tables.
- Monitor `age(datfrozenxid)` continuously. Wraparound is entirely predictable and entirely
  preventable, and it is the one database failure with no quick recovery.
- Alert on inactive replication slots. An abandoned slot silently disables cleanup everywhere and
  fills the disk at the same time.
- Keep transactions short, which is the same advice as DBH003 and DBH005 and fixes all three.

## Escalate

Escalate to the database owner if:

- `xids_remaining` is under 200 million -- this is now a scheduled outage waiting to happen;
- five or more tables are affected, which means autovacuum is blocked globally rather than
  struggling with one table;
- dead tuples keep growing after a manual `VACUUM`, which means something still holds the
  snapshot;
- an inactive replication slot is retaining a large amount of WAL, since disk exhaustion may
  arrive before the vacuum problem does.
