# DI007 -- Calendar sync wrote the same event more than once

**Severity** HIGH, escalating to CRITICAL at 50 rows
**Group** Data integrity
**What the user sees** A meeting appears twice and the room looks busier than it really is.

## What this means

`external_event_id` is the idempotency key the calendar sync uses to recognise an event it has
already imported. The column has no `UNIQUE` constraint, so when a sync run times out after
writing but before recording success, the retry writes the event again.

The duplicates consume room availability twice, so rooms show as booked when they are free. They
can also produce DI002 findings that are not real double-bookings but the same meeting counted
twice -- **resolve DI007 before acting on DI002**.

`distinct_rooms` and `distinct_employees` in the finding tell you whether the copies are
identical (a plain retry) or divergent (the event was edited between copies, which needs care).

## Confirm

Look at the copies side by side:

```sql
SELECT booking_id, room_id, employee_id, starts_at, ends_at,
       status, created_at, updated_at
FROM bookings
WHERE external_event_id = :external_event_id
ORDER BY booking_id;
```

If `created_at` values are seconds apart and every other field matches, it is a retry. If they
are hours apart or the times differ, the event was edited and the sync created a second row
instead of updating the first -- a different bug with the same symptom.

Check the blast radius and whether it is ongoing:

```sql
SELECT date_trunc('hour', created_at) AS hour,
       count(*) AS duplicate_rows
FROM bookings b
WHERE external_event_id IN (
        SELECT external_event_id FROM bookings
        WHERE external_event_id IS NOT NULL AND status <> 'cancelled'
        GROUP BY external_event_id HAVING count(*) > 1)
  AND created_at > now() - interval '48 hours'
GROUP BY 1 ORDER BY 1;
```

Cross-check `sync_job_runs` for failures around those times -- a retry storm usually has a
matching cluster of failed or stuck runs, which is OPS001 and OPS002 territory.

## Fix

Keep the oldest copy of each event and cancel the rest. Oldest, not newest: the first row is the
one whose `booking_id` other things may already reference.

```sql
-- Preview exactly what will be cancelled.
WITH ranked AS (
    SELECT booking_id, external_event_id,
           row_number() OVER (PARTITION BY external_event_id ORDER BY booking_id) AS copy_number
    FROM bookings
    WHERE external_event_id IS NOT NULL
      AND status <> 'cancelled'
      AND starts_at >= now() - interval '7 days'
)
SELECT * FROM ranked WHERE copy_number > 1 ORDER BY external_event_id, copy_number;

-- Then cancel the extras.
BEGIN;
WITH ranked AS (
    SELECT booking_id,
           row_number() OVER (PARTITION BY external_event_id ORDER BY booking_id) AS copy_number
    FROM bookings
    WHERE external_event_id IS NOT NULL
      AND status <> 'cancelled'
      AND starts_at >= now() - interval '7 days'
)
UPDATE bookings SET status = 'cancelled', updated_at = now()
WHERE booking_id IN (SELECT booking_id FROM ranked WHERE copy_number > 1);
-- Confirm the row count matches the preview, then COMMIT.
COMMIT;
```

If the copies diverge, do not automate it. Decide by hand which version is correct -- usually the
most recently updated -- and cancel the others.

## Prevent

Add the constraint that makes the retry idempotent instead of duplicative:

```sql
-- Partial, because most bookings are not from the calendar and have a NULL key.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS bookings_external_event_uniq
    ON bookings (external_event_id)
    WHERE external_event_id IS NOT NULL;
```

Then change the sync to `INSERT ... ON CONFLICT (external_event_id) DO UPDATE`, so a retry
updates the existing row rather than failing or duplicating. That is what makes the retry safe,
and it also fixes the "event was edited" variant at the same time.

Clear the backlog first, or the index build fails. `CONCURRENTLY` avoids taking an exclusive lock
on a large table; it takes longer but does not block writes.

## Escalate

Escalate to the application on-call if:

- duplicates are still being created -- check the hourly count above;
- more than 50 events are affected, which suggests a retry storm rather than a single timeout;
- the copies diverge in room or time, which is a different and worse bug than a retry;
- OPS001 or OPS002 are also reporting, since a sync that is failing and retrying is the mechanism
  producing these and fixing the sync comes first.
