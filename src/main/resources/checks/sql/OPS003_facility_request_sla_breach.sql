-- OPS003 -- facility tickets past the SLA for their priority.
-- p1 ${sla_p1_hours}h, p2 ${sla_p2_hours}h, p3 ${sla_p3_hours}h -- tune with
-- --threshold sla_p2_hours=12. Measured from opened_at because that is when the clock starts
-- for the requester regardless of when anyone acknowledged it. Cancelled and resolved tickets
-- cannot breach.
--
-- Sorted and ordered worst-overrun first, which is the order an on-call works the queue in.
SELECT fr.request_id,
       fr.priority,
       round(EXTRACT(epoch FROM (now() - fr.opened_at)) / 3600.0 - sla.sla_hours, 1)
           AS hours_over_sla,
       round(EXTRACT(epoch FROM (now() - fr.opened_at)) / 3600.0, 1) AS open_hours,
       sla.sla_hours,
       fr.status,
       fr.acknowledged_at IS NOT NULL AS acknowledged,
       fr.category,
       fr.site_id,
       fr.room_id,
       fr.opened_at
FROM facility_requests fr
CROSS JOIN LATERAL (
    SELECT CASE fr.priority
               WHEN 'p1' THEN ${sla_p1_hours}
               WHEN 'p2' THEN ${sla_p2_hours}
               ELSE ${sla_p3_hours}
           END AS sla_hours
) sla
WHERE fr.status IN ('open', 'in_progress')
  AND now() - fr.opened_at > make_interval(hours => sla.sla_hours)
ORDER BY 3 DESC
