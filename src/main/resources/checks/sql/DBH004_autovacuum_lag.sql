-- DBH004 -- tables where dead rows are piling up faster than autovacuum clears them.
-- Dead tuples are the slow-motion outage: queries get gradually slower as scans wade through
-- rows nobody can see, and in the worst case the transaction ID counter approaches wraparound.
-- Two conditions must both hold, so that a small table with a high percentage (a 6-row table
-- with 3 dead rows is 50% dead and completely fine) does not page anybody.
SELECT schemaname,
       relname,
       n_live_tup,
       n_dead_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       last_vacuum,
       last_autovacuum,
       autovacuum_count,
       n_mod_since_analyze
FROM pg_stat_user_tables
WHERE n_dead_tup >= ${dead_tuples_min}
  AND round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 1) >= ${dead_tuple_pct}
ORDER BY n_dead_tup DESC
