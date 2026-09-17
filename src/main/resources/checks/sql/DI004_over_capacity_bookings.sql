-- DI004 -- more attendees invited than the room holds.
-- Not a data corruption so much as a guaranteed bad meeting: people arrive and cannot sit.
-- Booking UIs validate this client-side, so violations mean the API or an import bypassed it.
SELECT b.booking_id,
       b.room_id,
       r.name AS room_name,
       r.capacity,
       b.attendee_count,
       b.attendee_count - r.capacity AS over_by,
       b.starts_at,
       b.source
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id
WHERE b.status = 'confirmed'
  AND b.starts_at >= now() - interval '${booking_window_days} days'
  AND b.attendee_count > r.capacity
ORDER BY (b.attendee_count - r.capacity) DESC, b.starts_at
