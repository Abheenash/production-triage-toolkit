# DI004 -- Booking with more attendees than the room holds

**Severity** MEDIUM, escalating to HIGH at 250 rows
**Group** Data integrity
**What the user sees** People arrive at a meeting and there is nowhere for them to sit.

## What this means

`attendee_count` exceeds the room's `capacity`. The booking UI validates this in the browser, so
a violation means something bypassed the UI: the API accepting a request the client would have
rejected, a bulk import, or a room whose capacity was reduced after the bookings were made.

That last case is the common one and is nobody's fault -- a refit removes four chairs and every
existing booking for that room is retrospectively too big.

## Confirm

Separate the two causes. If the violations cluster on a few rooms, capacity changed; if they are
scattered across many rooms, the write path is not validating:

```sql
SELECT b.room_id, r.name, r.capacity,
       count(*)              AS over_capacity_bookings,
       max(b.attendee_count) AS worst_case,
       min(b.created_at)     AS first_booked,
       max(b.created_at)     AS last_booked
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id
WHERE b.status = 'confirmed'
  AND b.starts_at >= now() - interval '7 days'
  AND b.attendee_count > r.capacity
GROUP BY b.room_id, r.name, r.capacity
ORDER BY over_capacity_bookings DESC;
```

Check `source`. If every offending row has `source = 'calendar_sync'` or a single import batch,
you have found the path that skips validation:

```sql
SELECT source, count(*) FROM bookings b JOIN rooms r USING (room_id)
WHERE b.attendee_count > r.capacity AND b.starts_at >= now() - interval '7 days'
GROUP BY source;
```

## Fix

Only upcoming meetings matter. Work them worst-overrun first, since a meeting for 15 in a room
for 4 is a different problem from 9 in a room for 8.

```sql
-- A bigger room, same site, free at that time.
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

Move the booking and tell the organiser. If nothing is free, tell them anyway -- an organiser who
knows the room is too small can cut the invite list or make it hybrid. Silence is the only
outcome that guarantees a bad meeting.

Do not "fix" this by editing `attendee_count` down to fit. The number of people expected is a
fact about the meeting, not a field to be adjusted until the constraint passes.

## Prevent

- Validate on the server, not only in the browser. Every write path -- API, calendar sync,
  import -- must check capacity, because the browser is not in the loop for two of them.
- When a room's capacity is reduced, check for existing bookings above the new number in the same
  change, and notify those organisers. A capacity edit is a data migration, not a settings tweak.
- A database constraint cannot easily express this, since it spans two tables and capacity can
  legitimately change afterwards. Leave it to the application and to this check.

## Escalate

Escalate to the application on-call if:

- the count is above 250, or spread across many rooms, which means a write path has no validation
  at all;
- every offending row shares one `source`, which identifies that path and makes it fixable today;
- an affected meeting is within 2 hours and no alternative room exists, so somebody needs to make
  a decision rather than a database change.
