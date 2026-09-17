-- DBH005 -- sessions holding a transaction open while doing nothing.
-- An idle-in-transaction session keeps its locks and pins the oldest snapshot, which stops
-- autovacuum from cleaning ANY table in the database. One forgotten psql window can
-- therefore cause bloat everywhere. The usual cause is an application that took a connection
-- from the pool, began a transaction, and then made a slow network call.
SELECT pid,
       usename,
       application_name,
       client_addr,
       state,
       round(EXTRACT(epoch FROM (now() - state_change)), 1) AS idle_seconds,
       round(EXTRACT(epoch FROM (now() - xact_start)), 1)   AS transaction_seconds,
       backend_xid,
       backend_xmin,
       left(regexp_replace(query, '\s+', ' ', 'g'), 160) AS last_query
FROM pg_stat_activity
WHERE backend_type = 'client backend'
  AND state IN ('idle in transaction', 'idle in transaction (aborted)')
  AND pid <> pg_backend_pid()
  AND state_change < now() - make_interval(secs => ${idle_in_transaction_seconds})
ORDER BY xact_start
