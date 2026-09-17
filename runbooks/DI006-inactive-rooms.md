# DI006 -- Future booking in a decommissioned room

**Severity** MEDIUM, escalating to HIGH at 20 rows
**Group** Data integrity
**What the user sees** Someone walks to a room that is now a store cupboard or a building site.

## What this means

A room has `is_active = false` -- it has been taken out of service -- but confirmed bookings for
it still exist in the future. Retiring a room updated the room, and nothing else.

Past bookings on a retired room are ordinary history and this check ignores them. Only the future
matters, because only the future can still go wrong.

`hours_until_start` sets the urgency. Under two hours needs a phone call; next month needs an
email.

## Confirm

Group by room to see whether one retirement was missed or several:

```sql
SELECT r.room_id, r.name, r.decommissioned_at,
       count(*) AS upcoming_bookings,
       min(b.starts_at) AS next_one
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id
WHERE NOT r.is_active AND b.status = 'confirmed' AND b.starts_at >= now()
GROUP BY r.room_id, r.name, r.decommissioned_at
ORDER BY next_one;
```

Check when the room was retired. If `decommissioned_at` is recent, the retirement process simply
has not finished. If it is months ago, the process does not handle future bookings at all and
every retirement since has left the same mess.

## Fix

Every affected organiser needs telling and rebooking. This is not a change you can make silently,
because people have already told attendees where to go.

```sql
-- Who is affected.
SELECT b.booking_id, e.full_name, e.email, r.name AS dead_room,
       b.starts_at, b.attendee_count
FROM bookings b
JOIN rooms r ON r.room_id = b.room_id
JOIN employees e ON e.employee_id = b.employee_id
WHERE NOT r.is_active AND b.status = 'confirmed' AND b.starts_at >= now()
ORDER BY b.starts_at;

-- Alternatives at the same site, same time, large enough.
SELECT r2.room_id, r2.name, r2.capacity
FROM rooms r2
WHERE r2.is_active
  AND r2.site_id = (SELECT site_id FROM rooms WHERE room_id = :dead_room_id)
  AND r2.capacity >= :attendee_count
  AND NOT EXISTS (
        SELECT 1 FROM bookings x
        WHERE x.room_id = r2.room_id AND x.status = 'confirmed'
          AND x.starts_at < :ends_at AND :starts_at < x.ends_at)
ORDER BY r2.capacity
LIMIT 5;
```

Work in `starts_at` order: the soonest meeting is the one most likely to end with people standing
in a corridor.

## Prevent

Make retiring a room a process rather than a flag:

1. Set `is_active = false` so no new bookings can be made.
2. In the same change, list future confirmed bookings for that room.
3. Notify those organisers and either move or cancel each one.
4. Only then consider the retirement complete.

Enforcing step 1 in the booking path matters as much as the rest -- if the API does not filter on
`is_active`, new bookings will keep arriving for a room that no longer exists and this check will
never come clean.

A partial index makes both the check and the application filter cheap:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS rooms_inactive_idx
    ON rooms (room_id) WHERE NOT is_active;
```

## Escalate

Escalate to facilities and the application on-call if:

- any affected meeting starts within 2 hours -- somebody needs to be phoned, not emailed;
- more than 20 bookings are affected, which means several retirements were left half-finished;
- bookings for the retired room are still being *created* (check `created_at`), which means the
  booking path is not filtering on `is_active` and the backlog will keep regrowing.
