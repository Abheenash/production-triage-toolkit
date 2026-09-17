-- DBH001 -- how close the server is to refusing new connections.
-- Running out of connections presents to users as a total outage, and it arrives without
-- warning: everything is fine at 90% and dead at 100%. superuser_reserved_connections is
-- subtracted because those slots are not available to the application.
-- Emits at most one row, and only once past the threshold.
SELECT current_setting('max_connections')::int AS max_connections,
       current_setting('superuser_reserved_connections')::int AS reserved_for_superuser,
       count(*)                                                 AS client_connections,
       count(*) FILTER (WHERE state = 'active')                 AS active,
       count(*) FILTER (WHERE state = 'idle')                   AS idle,
       count(*) FILTER (WHERE state LIKE 'idle in transaction%') AS idle_in_transaction,
       count(DISTINCT usename)                                  AS distinct_users,
       round(100.0 * count(*)
             / NULLIF(current_setting('max_connections')::numeric
                      - current_setting('superuser_reserved_connections')::numeric, 0), 1)
           AS pct_of_usable_slots
FROM pg_stat_activity
WHERE backend_type = 'client backend'
HAVING round(100.0 * count(*)
             / NULLIF(current_setting('max_connections')::numeric
                      - current_setting('superuser_reserved_connections')::numeric, 0), 1)
       >= ${connection_pct}
