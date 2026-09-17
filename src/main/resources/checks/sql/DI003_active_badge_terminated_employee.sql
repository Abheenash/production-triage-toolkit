-- DI003 -- a terminated employee whose badge still opens doors.
-- The HR feed deactivates badges on termination. When it fails, nothing visible breaks:
-- the badge simply keeps working.
--
-- Column order is the order the security team asks the questions in: who is it, how long
-- ago did they leave, and -- the one that decides whether this is paperwork or an incident
-- -- has the badge actually been used since?
SELECT bg.badge_id,
       bg.badge_number,
       e.employee_id,
       e.full_name,
       e.terminated_at,
       round(EXTRACT(epoch FROM (now() - e.terminated_at)) / 86400.0, 1) AS days_since_termination,
       s.scans_since_termination,
       s.last_scan_at,
       e.email
FROM badges bg
JOIN employees e ON e.employee_id = bg.employee_id
LEFT JOIN LATERAL (
    SELECT max(sc.scanned_at) AS last_scan_at,
           count(*) FILTER (WHERE sc.scanned_at > e.terminated_at) AS scans_since_termination
    FROM badge_scans sc
    WHERE sc.badge_id = bg.badge_id
) s ON true
WHERE e.status = 'terminated'
  AND bg.is_active
ORDER BY s.scans_since_termination DESC NULLS LAST, e.terminated_at
