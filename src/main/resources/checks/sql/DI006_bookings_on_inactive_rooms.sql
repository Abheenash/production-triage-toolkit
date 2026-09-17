-- DI006 -- a future meeting in a room that has been decommissioned.
-- Past bookings on a dead room are ordinary history and are ignored. A FUTURE confirmed
-- booking means someone will walk to a room that is a store cupboard or a building site.
-- hours_until_start is first after the identifiers because it decides the order of work:
-- a meeting in 40 minutes needs a phone call, one in three weeks needs an email.
SELECT b.booking_id,
       b.room_id,
       r.name AS room_name,
       round(EXTRACT(epoch FROM (b.starts_at - now())) / 3600.0, 1) AS hours_until_start,
       b.starts_at,
       b.employee_id,
       r.decommissioned_at
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id
WHERE b.status = 'confirmed'
  AND b.starts_at >= now()
  AND NOT r.is_active
ORDER BY b.starts_at
