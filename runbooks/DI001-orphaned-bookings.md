# DI001 -- Bookings pointing at a room that no longer exists

**Severity** HIGH, escalating to CRITICAL at 100 rows
**Group** Data integrity
**What the user sees** The booking page shows a blank room name, or errors outright when opened.

## What this means

`bookings.room_id` has no foreign key. A room deleted by an admin, a cleanup script, or a
botched migration takes nothing with it, so its bookings survive pointing at an id that is no
longer in `rooms`.

The row count tells you which of two very different incidents you have. A handful means somebody
deleted a few rooms by hand. Hundreds means a migration or a bulk delete ran without checking
dependants, and there is probably more collateral damage than this one check found.

## Confirm

Find out whether this is a few rooms or many, and whether the bookings are in the past or ahead:

```sql
SELECT b.room_id,
       count(*)                                    AS orphaned_bookings,
       count(*) FILTER (WHERE b.starts_at > now()) AS still_upcoming,
       min(b.starts_at)                            AS earliest,
       max(b.starts_at)                            AS latest
FROM bookings b
LEFT JOIN rooms r ON r.room_id = b.room_id
WHERE r.room_id IS NULL
GROUP BY b.room_id
ORDER BY orphaned_bookings DESC;
```

Then check whether the rooms were deleted or never existed. If `room_id` values are far outside
the range in `rooms`, they were never real and the cause is an import, not a delete:

```sql
SELECT min(room_id), max(room_id), count(*) FROM rooms;
```

## Fix

Only upcoming bookings need action; past ones are history and rewriting history makes reporting
wrong. Decide per room:

1. **The room still exists physically, the row was deleted by mistake.** Restore the row from a
   backup or recreate it with the same `room_id`. The bookings heal themselves, because nothing
   about them was wrong.
2. **The room is genuinely gone.** Cancel the upcoming bookings and tell the organisers. Never
   silently reassign them to another room -- people have told attendees where to go.

```sql
-- Preview first. Always.
SELECT booking_id, room_id, employee_id, starts_at
FROM bookings b
WHERE NOT EXISTS (SELECT 1 FROM rooms r WHERE r.room_id = b.room_id)
  AND starts_at > now()
ORDER BY starts_at;

-- Then, in a transaction, with the list above in front of you:
BEGIN;
UPDATE bookings b
   SET status = 'cancelled', updated_at = now()
 WHERE NOT EXISTS (SELECT 1 FROM rooms r WHERE r.room_id = b.room_id)
   AND starts_at > now()
   AND status <> 'cancelled';
-- Check the row count matches what you previewed, then COMMIT. Otherwise ROLLBACK.
COMMIT;
```

Export the affected organisers before cancelling, so somebody can tell them:

```sql
SELECT DISTINCT e.email, b.booking_id, b.starts_at
FROM bookings b
JOIN employees e ON e.employee_id = b.employee_id
WHERE NOT EXISTS (SELECT 1 FROM rooms r WHERE r.room_id = b.room_id)
  AND b.starts_at > now();
```

## Prevent

The permanent fix is the constraint the table never had. Clean the existing orphans first, or
adding it will fail:

```sql
ALTER TABLE bookings
  ADD CONSTRAINT bookings_room_id_fkey
  FOREIGN KEY (room_id) REFERENCES rooms(room_id)
  NOT VALID;                      -- new rows are checked immediately, existing rows are not

-- Once the backlog is cleaned, validate without a long exclusive lock:
ALTER TABLE bookings VALIDATE CONSTRAINT bookings_room_id_fkey;
```

`NOT VALID` matters on a 10M-row table: it takes a brief lock instead of scanning everything,
and stops the bleeding immediately while you work through the backlog.

Then decide what deleting a room should mean. Usually rooms should be retired
(`is_active = false`, which is what DI006 watches) rather than deleted at all.

## Escalate

Escalate to the application on-call if:

- the count is above 100, or grew between two runs -- something is still deleting rooms;
- any orphaned booking starts within 24 hours, since attendees need telling today;
- `room_id` values look like they were never valid, which means a broken import is writing
  garbage and will keep doing so until it is stopped.
