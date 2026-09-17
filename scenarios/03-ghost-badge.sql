-- Scenario 03 -> must be caught by DI003 (active badge, terminated employee), and nothing else.
--
-- Story: the nightly HR feed failed partway through. Three people left the company; their
-- employee records were updated but their badges were never deactivated. Nothing in the
-- product misbehaves -- the badges simply keep opening doors.
--
-- A door scan is added for one of the three, dated after their termination, so the finding
-- shows the difference between "a badge that could be used" and "a badge that is being used".
BEGIN;

UPDATE badges
   SET is_active = true,
       deactivated_at = NULL
 WHERE badge_id IN (
         SELECT bg.badge_id
         FROM badges bg
         JOIN employees e ON e.employee_id = bg.employee_id
         WHERE e.status = 'terminated'
           AND NOT bg.is_active
         ORDER BY bg.badge_id
         LIMIT 3
       );

INSERT INTO badge_scans (badge_id, site_id, scanned_at, result)
SELECT bg.badge_id, coalesce(e.home_site_id, 1), now() - interval '3 hours', 'granted'
FROM badges bg
JOIN employees e ON e.employee_id = bg.employee_id
WHERE e.status = 'terminated' AND bg.is_active
ORDER BY bg.badge_id
LIMIT 1;

COMMIT;
