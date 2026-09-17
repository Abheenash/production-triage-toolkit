-- Scenario 02 -> must be caught by DI002 (overlapping bookings), and by nothing else.
--
-- Story: a race in the booking API. Two requests for the same room passed their "is it
-- free?" read before either wrote, so both writes succeeded.
--
-- Three existing confirmed bookings are each cloned 10 minutes later. The seed grid puts
-- consecutive bookings in a room 4 hours apart and caps duration at 90 minutes, so a
-- 10-minute shift can only ever collide with its own original -- exactly three pairs, never
-- a chain. external_event_id is nulled on the clone so DI007 stays quiet.
BEGIN;

INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, attendee_count,
                      status, source, external_event_id, created_at, updated_at)
SELECT b.room_id,
       b.employee_id + 1,
       b.starts_at + interval '10 minutes',
       b.ends_at   + interval '10 minutes',
       b.attendee_count,
       'confirmed',
       'web',
       NULL,
       now(),
       now()
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id AND r.is_active
WHERE b.status = 'confirmed'
  AND b.starts_at >= now() - interval '2 days'
  AND b.starts_at <= now() + interval '2 days'
  AND b.ends_at > b.starts_at
  AND b.attendee_count <= r.capacity
ORDER BY b.booking_id
LIMIT 3;

COMMIT;
