-- DI002 -- the same room confirmed to two people at once.
--
-- The obvious way to write this is a self-join: pair every confirmed booking with every other
-- one in the same room and keep the pairs whose times overlap. That is correct, and at 10 million
-- rows it was the slowest thing the toolkit did -- roughly 2.1 seconds on its own, because the
-- planner turns it into one index probe per booking in the window, and the window holds about
-- 910,000 bookings. See docs/benchmark.md.
--
-- This version sorts instead of joining. Walking each room's bookings in start order, a booking
-- overlaps something earlier if and only if it starts before the LATEST END SEEN SO FAR in that
-- room. That is one window function over one sorted pass: O(n log n), and about four times
-- faster here.
--
-- The running maximum matters, and a simpler lag() would be wrong. If a three-hour booking is
-- followed by two short ones, the third booking's immediate predecessor is the second, which it
-- may not touch -- but it still clashes with the long first one. Comparing against the running
-- max of every earlier end catches that; comparing against the previous row does not.
--
-- Having found the clashing bookings cheaply, the LATERAL then looks up what each one actually
-- collides with. That lookup is the expensive shape from the first paragraph, but it now runs
-- once per finding rather than once per booking -- and on a healthy database there are no
-- findings, so it runs not at all.
--
-- One row per booking that overlaps something earlier, rather than one row per unordered pair.
-- booking_id_b is the later booking: the one to move or cancel.
WITH confirmed AS (
    SELECT booking_id, room_id, employee_id, starts_at, ends_at
    FROM bookings
    WHERE status = 'confirmed'
      AND starts_at >= now() - interval '${booking_window_days} days'
),
scanned AS (
    SELECT c.*,
           max(c.ends_at) OVER (
               PARTITION BY c.room_id
               ORDER BY c.starts_at, c.booking_id
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_max_end
    FROM confirmed c
),
clashing AS (
    -- Strictly greater: a booking that begins exactly when the previous one ends is not a clash.
    SELECT * FROM scanned WHERE prior_max_end > starts_at
)
SELECT c.room_id,
       r.name AS room_name,
       p.booking_id  AS booking_id_a,
       c.booking_id  AS booking_id_b,
       GREATEST(c.starts_at, p.starts_at) AS overlap_starts_at,
       LEAST(c.ends_at, p.ends_at)        AS overlap_ends_at,
       round(EXTRACT(epoch FROM (LEAST(c.ends_at, p.ends_at)
                               - GREATEST(c.starts_at, p.starts_at))) / 60.0, 1) AS overlap_minutes,
       p.employee_id AS employee_a,
       c.employee_id AS employee_b,
       p.starts_at AS a_starts_at,
       p.ends_at   AS a_ends_at,
       c.starts_at AS b_starts_at,
       c.ends_at   AS b_ends_at
FROM clashing c
CROSS JOIN LATERAL (
    SELECT b.booking_id, b.employee_id, b.starts_at, b.ends_at
    FROM bookings b
    WHERE b.room_id = c.room_id
      AND b.status = 'confirmed'
      AND b.booking_id <> c.booking_id
      AND b.starts_at < c.ends_at
      AND b.ends_at   > c.starts_at
    ORDER BY b.starts_at, b.booking_id
    LIMIT 1
) p
LEFT JOIN rooms r ON r.room_id = c.room_id
ORDER BY c.starts_at, c.room_id
