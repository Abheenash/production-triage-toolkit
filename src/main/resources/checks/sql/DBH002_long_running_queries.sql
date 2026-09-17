-- DBH002 -- queries that have been executing longer than they should.
-- Excludes this session (pg_backend_pid) so the toolkit never reports itself, and excludes
-- autovacuum and other background workers, which have their own lifecycles and are not a
-- query someone can cancel. wait_event tells you in one glance whether it is working or
-- waiting -- a query blocked on a lock is a different incident from a query doing a big scan.
SELECT pid,
       usename,
       application_name,
       client_addr,
       state,
       round(EXTRACT(epoch FROM (now() - query_start)), 1) AS running_seconds,
       wait_event_type,
       wait_event,
       left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS query_snippet
FROM pg_stat_activity
WHERE backend_type = 'client backend'
  AND state = 'active'
  AND pid <> pg_backend_pid()
  AND query_start IS NOT NULL
  AND query_start < now() - make_interval(secs => ${long_query_seconds})
ORDER BY query_start
