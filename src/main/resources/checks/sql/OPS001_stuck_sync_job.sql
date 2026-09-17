-- OPS001 -- a sync run that started and never finished.
-- The job has no watchdog: a run whose process was OOM-killed stays in status='running'
-- forever, and the scheduler's "don't start if one is already running" guard then blocks
-- every later run. Bookings stop syncing and nothing raises an error anywhere.
--
-- Two ways to be stuck, and stuck_reason says which, because they need different responses:
-- a dead heartbeat means the process is gone and the row needs clearing; a live heartbeat on
-- a run that has overrun means the process is alive and wedged on something upstream.
SELECT run_id,
       job_name,
       CASE WHEN heartbeat_at IS NULL THEN 'never sent a heartbeat'
            WHEN now() - heartbeat_at > make_interval(mins => ${stuck_job_minutes})
                 THEN 'heartbeat stopped advancing'
            ELSE 'alive but running past its expected duration' END AS stuck_reason,
       round(EXTRACT(epoch FROM (now() - started_at)) / 60.0, 1) AS running_minutes,
       round(EXTRACT(epoch FROM (now() - coalesce(heartbeat_at, started_at))) / 60.0, 1)
           AS heartbeat_age_minutes,
       rows_processed,
       started_at,
       heartbeat_at
FROM sync_job_runs
WHERE status = 'running'
  AND (now() - started_at > make_interval(mins => ${stuck_job_minutes})
       OR now() - coalesce(heartbeat_at, started_at) > make_interval(mins => ${stuck_job_minutes}))
ORDER BY started_at
