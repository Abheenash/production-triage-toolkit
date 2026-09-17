-- DI007 -- the calendar sync wrote the same external event more than once.
-- external_event_id is the sync's idempotency key but carries no UNIQUE constraint, so a
-- retry after a timeout inserts a second copy. The user sees their meeting twice and the
-- room looks busier than it is.
SELECT b.external_event_id,
       count(*)                       AS booking_count,
       min(b.booking_id)              AS first_booking_id,
       max(b.booking_id)              AS latest_booking_id,
       min(b.starts_at)               AS earliest_start,
       count(DISTINCT b.room_id)      AS distinct_rooms,
       count(DISTINCT b.employee_id)  AS distinct_employees
FROM bookings b
WHERE b.external_event_id IS NOT NULL
  AND b.status <> 'cancelled'
  AND b.starts_at >= now() - interval '${booking_window_days} days'
GROUP BY b.external_event_id
HAVING count(*) > 1
ORDER BY count(*) DESC, min(b.booking_id)
