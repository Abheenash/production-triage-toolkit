# DI002 -- One room confirmed to two people at the same time

**Severity** HIGH, escalating to CRITICAL at 25 rows
**Group** Data integrity
**What the user sees** Two groups arrive for the same room and one of them has to leave.

## What this means

Two confirmed bookings for one room overlap in time. The usual cause is a race in the booking
API: two requests both read "the room is free" before either wrote, and both writes then
succeeded because nothing in the database stops them.

Each row in the finding is one *pair*. Three rows can be three separate clashes, or one room
triple-booked -- `booking_id_a` repeating across rows tells you which.

## Confirm

Look at the clash in context, including anything adjacent that the check did not pair up:

```sql
SELECT booking_id, employee_id, starts_at, ends_at, status, source, created_at
FROM bookings
WHERE room_id = :room_id
  AND starts_at BETWEEN :overlap_start - interval '4 hours'
                    AND :overlap_start + interval '4 hours'
ORDER BY starts_at;
```

`created_at` usually settles who booked first, and `source` tells you whether the calendar sync
was involved. If both rows were created within a second or two of each other, it is the API race.
If they are hours or days apart, something wrote without checking availability at all.

Check whether this is systemic rather than a one-off:

```sql
SELECT date_trunc('day', a.starts_at) AS day, count(*) AS clashing_pairs
FROM bookings a
JOIN bookings b ON b.room_id = a.room_id AND b.booking_id > a.booking_id
              AND b.status = 'confirmed'
              AND b.starts_at < a.ends_at AND a.starts_at < b.ends_at
WHERE a.status = 'confirmed'
  AND a.starts_at > now() - interval '30 days'
GROUP BY 1 ORDER BY 1;
```

A flat daily count is an ongoing bug. A single spike is an incident that has already ended.

## Fix

The later booking loses, and the person who made it gets told and helped to rebook. Do not simply
cancel it and walk away -- they believe they have a room.

```sql
-- Who to contact, before changing anything.
SELECT b.booking_id, e.full_name, e.email, b.starts_at, b.ends_at
FROM bookings b JOIN employees e ON e.employee_id = b.employee_id
WHERE b.booking_id = :later_booking_id;

-- Find them somewhere else at the same time, same site, big enough.
SELECT r.room_id, r.name, r.capacity
FROM rooms r
WHERE r.is_active
  AND r.site_id = (SELECT site_id FROM rooms WHERE room_id = :room_id)
  AND r.capacity >= :attendee_count
  AND NOT EXISTS (
        SELECT 1 FROM bookings x
        WHERE x.room_id = r.room_id AND x.status = 'confirmed'
          AND x.starts_at < :ends_at AND :starts_at < x.ends_at)
ORDER BY r.capacity
LIMIT 5;
```

For meetings starting within the hour, phone rather than email.

## Prevent

PostgreSQL can make this impossible rather than merely detectable, with an exclusion constraint:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE bookings
  ADD CONSTRAINT bookings_no_overlap
  EXCLUDE USING gist (
      room_id WITH =,
      tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status = 'confirmed');
```

The `'[)'` bound is deliberate: a booking that ends exactly when the next begins is fine, and a
`'[]'` range would reject it. The `WHERE` clause keeps tentative and cancelled holds out of it.

This converts the race from a silent double-booking into a constraint violation the API must
handle -- so pair it with an application change that catches the violation and returns "that room
was just taken" rather than a 500. Clear the existing overlaps before adding it, or it will not
build.

Building the index takes an exclusive lock. On a large table, create it concurrently first and
then attach it, or schedule the change for a quiet window.

## Escalate

Escalate to the application on-call if:

- the clash count is rising between runs -- the race is live and still producing bad data;
- more than 25 pairs exist, which is far beyond what hand-correction can keep up with;
- any clash starts within 2 hours, because two groups are about to collide in a doorway.
