-- DBH003 -- sessions stuck waiting on a lock somebody else holds.
-- This is the check that turns "the app is slow" into a pid you can act on: it names both
-- the victim and the blocker. pg_blocking_pids() is authoritative -- it resolves the full
-- wait graph, unlike joining pg_locks by hand, which misses several lock types.
SELECT blocked.pid                       AS blocked_pid,
       blocked.usename                   AS blocked_user,
       blocked.application_name          AS blocked_application,
       round(EXTRACT(epoch FROM (now() - blocked.query_start)), 1) AS blocked_seconds,
       blocked.wait_event_type,
       blocked.wait_event,
       blocking.pid                      AS blocking_pid,
       blocking.usename                  AS blocking_user,
       blocking.state                    AS blocking_state,
       round(EXTRACT(epoch FROM (now() - blocking.state_change)), 1) AS blocking_state_seconds,
       left(regexp_replace(blocked.query,  '\s+', ' ', 'g'), 120) AS blocked_query,
       left(regexp_replace(blocking.query, '\s+', ' ', 'g'), 120) AS blocking_query
FROM pg_stat_activity blocked
CROSS JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS bp(blocking_pid)
JOIN pg_stat_activity blocking ON blocking.pid = bp.blocking_pid
WHERE blocked.backend_type = 'client backend'
  AND blocked.pid <> pg_backend_pid()
  AND cardinality(pg_blocking_pids(blocked.pid)) > 0
ORDER BY blocked_seconds DESC
