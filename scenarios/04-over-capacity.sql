-- Scenario 04 -> must be caught by DI004 (over-capacity bookings), and by nothing else.
--
-- Story: a bulk import from the old system carried attendee counts across without
-- re-validating them against the new room capacities, which changed after a refit.
--
-- Only attendee_count is touched, so no timestamp moves and DI002/DI005 cannot be disturbed.
--
-- OFFSET 20 is not arbitrary. Without it this scenario selects whichever bookings are newest,
-- which after scenario 02 has run are scenario 02's own inserted clones -- the two scenarios
-- would then overlap on the same rows and neither would be proving what it claims to prove.
-- Skipping the first 20 rows of the window puts scenario 04 on a slice that scenarios 01 and
-- 02 never touch, so the six can be injected in any order, or individually.
BEGIN;

UPDATE bookings b
   SET attendee_count = (r.capacity + 1 + (b.booking_id % 9))::smallint
  FROM rooms r
 WHERE r.room_id = b.room_id
   AND b.booking_id IN (
         SELECT b2.booking_id
         FROM bookings b2
         JOIN rooms r2 ON r2.room_id = b2.room_id AND r2.is_active
         WHERE b2.status = 'confirmed'
           AND b2.starts_at >= now() - interval '2 days'
           AND b2.starts_at <= now() + interval '2 days'
         ORDER BY b2.booking_id
         LIMIT 3 OFFSET 20
       );

COMMIT;
