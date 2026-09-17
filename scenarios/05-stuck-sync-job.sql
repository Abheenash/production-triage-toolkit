-- Scenario 05 -> must be caught by OPS001 (stuck sync job), and by nothing else.
--
-- Story: three calendar-sync runs died without updating their row. Three different shapes,
-- because they fail differently in practice and the check has to catch all three:
--
--   1. OOM-killed mid-run    -- heartbeat stopped advancing 70 minutes ago
--   2. lost its DB session   -- started 3 hours ago, never wrote a heartbeat at all
--   3. wedged on a slow API  -- heartbeat is FRESH, but the run has been going 4 hours
--
-- Case 3 is the one a naive "heartbeat is stale" check misses: the process is alive and
-- dutifully reporting, it is just never going to finish.
--
-- OPS002 (stale job) must stay quiet, and does: the most recent SUCCESSFUL run is still the
-- one the seed left 5 minutes ago. Nothing here claims success.
BEGIN;

INSERT INTO sync_job_runs (job_name, started_at, finished_at, status, heartbeat_at,
                           rows_processed, error_message)
VALUES
  ('calendar_sync', now() - interval '95 minutes', NULL, 'running',
   now() - interval '70 minutes', 1840, NULL),

  ('calendar_sync', now() - interval '3 hours', NULL, 'running',
   NULL, 0, NULL),

  ('calendar_sync', now() - interval '4 hours', NULL, 'running',
   now() - interval '20 seconds', 96412, NULL);

COMMIT;
