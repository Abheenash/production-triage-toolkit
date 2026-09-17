-- DI005 -- a booking that ends before, or exactly when, it starts.
-- There is no CHECK (ends_at > starts_at) on the table (see db/schema.sql). Zero-length and
-- inverted ranges come from timezone-arithmetic bugs and from bulk imports. They make every
-- downstream range query behave unpredictably, including DI002 above.
SELECT booking_id,
       room_id,
       employee_id,
       starts_at,
       ends_at,
       round(EXTRACT(epoch FROM (ends_at - starts_at)) / 60.0, 1) AS duration_minutes,
       status,
       source
FROM bookings
WHERE starts_at >= now() - interval '${booking_window_days} days'
  AND ends_at <= starts_at
ORDER BY (ends_at - starts_at), booking_id
