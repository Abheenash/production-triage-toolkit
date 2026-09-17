-- OPS002 -- a job that has not succeeded recently enough, whatever the reason.
-- OPS001 catches a run wedged in 'running'. This catches the other shapes of silence: the
-- scheduler stopped firing, every run is failing, or the job was quietly disabled. One row
-- per job name, so a job that vanished entirely still reports.
SELECT job_name,
       max(finished_at) FILTER (WHERE status = 'succeeded') AS last_success_at,
       round(EXTRACT(epoch FROM (now() - max(finished_at) FILTER (WHERE status = 'succeeded')))
             / 3600.0, 1) AS hours_since_success,
       count(*) FILTER (WHERE status = 'failed'
                          AND started_at > now() - interval '24 hours') AS failed_runs_24h,
       count(*) FILTER (WHERE status = 'running')                       AS runs_in_progress,
       max(started_at)                                                  AS last_attempt_at
FROM sync_job_runs
GROUP BY job_name
HAVING max(finished_at) FILTER (WHERE status = 'succeeded') IS NULL
    OR max(finished_at) FILTER (WHERE status = 'succeeded')
       < now() - make_interval(hours => ${stale_job_hours})
ORDER BY 3 DESC NULLS FIRST
