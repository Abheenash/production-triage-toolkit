-- Scenario 01 -> must be caught by DI001 (orphaned bookings), and by nothing else.
--
-- Story: a cleanup script deleted rooms that looked unused, without checking for bookings.
-- Because bookings.room_id has no foreign key, the delete succeeded and left the bookings
-- pointing at nothing.
--
-- Each victim gets a DISTINCT non-existent room id (900000 + booking_id). That matters:
-- if they all shared one fake room they could overlap each other and DI002 would fire too,
-- and the scenario would no longer prove that DI001 specifically works.
BEGIN;

UPDATE bookings
   SET room_id = 900000 + booking_id
 WHERE booking_id IN (
         SELECT b.booking_id
         FROM bookings b
         JOIN rooms r ON r.room_id = b.room_id
         WHERE b.starts_at >= now() - interval '2 days'
           AND b.starts_at <= now() + interval '2 days'
           AND b.status = 'confirmed'
         ORDER BY b.booking_id
         LIMIT 3
       );

COMMIT;
