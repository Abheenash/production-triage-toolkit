-- DI001 -- bookings pointing at a room that does not exist.
-- bookings.room_id has no foreign key (see db/schema.sql), so a room deleted by a cleanup
-- script or an admin leaves its bookings behind. The user experience is a booking that
-- renders with a blank room name, or a 500 on the room detail page.
SELECT b.booking_id,
       b.room_id AS missing_room_id,
       b.employee_id,
       b.starts_at,
       b.status,
       b.source
FROM bookings b
LEFT JOIN rooms r ON r.room_id = b.room_id
WHERE b.starts_at >= now() - interval '${booking_window_days} days'
  AND r.room_id IS NULL
ORDER BY b.starts_at DESC, b.booking_id
