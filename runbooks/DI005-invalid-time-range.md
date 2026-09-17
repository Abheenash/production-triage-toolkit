# DI005 -- Booking that ends before it starts

**Severity** HIGH, escalating to CRITICAL at 50 rows
**Group** Data integrity
**What the user sees** The room shows as free when it is not, and availability maths goes wrong.

## What this means

`ends_at` is earlier than or equal to `starts_at`. The table has no
`CHECK (ends_at > starts_at)`, so nothing rejects it.

This is worse than one broken row. Every availability query uses a range comparison, and an
inverted or zero-length range does not behave like a short meeting -- it behaves like no meeting
at all, or matches nothing, depending on how the query is written. DI002 cannot see a clash
involving an inverted range either, so **fix DI005 before trusting DI002**.

Two distinct causes, told apart by the duration:

- **Exactly zero minutes** -- a UI or API that allowed an end equal to the start, or a default
  duration of zero.
- **Negative, often by a whole number of hours** -- timezone arithmetic. A start converted in one
  zone and an end in another; `-60` or `-300` minutes is a strong tell, and daylight-saving
  transitions produce exactly this.

## Confirm

The shape of the durations tells you which bug you have:

```sql
SELECT round(EXTRACT(epoch FROM (ends_at - starts_at)) / 60.0) AS duration_minutes,
       count(*),
       min(created_at) AS first_seen,
       max(created_at) AS last_seen,
       array_agg(DISTINCT source) AS sources
FROM bookings
WHERE ends_at <= starts_at
GROUP BY 1
ORDER BY 1;
```

Check whether it is still happening:

```sql
SELECT date_trunc('hour', created_at) AS hour, count(*)
FROM bookings
WHERE ends_at <= starts_at AND created_at > now() - interval '48 hours'
GROUP BY 1 ORDER BY 1;
```

Rows appearing in the current hour mean the bug is live and correcting the data now is premature.

## Fix

Correcting these requires knowing what the meeting was meant to be, and the row no longer says.
Do not guess a duration -- a 30-minute default applied to a meeting that was an hour creates a
false clash with whatever follows.

1. **Stop new bad rows first** if the hourly count above shows it is ongoing. Deploy the fix or
   disable the offending path. There is no point cleaning a table that is still filling up.
2. **Contact the organisers** of anything upcoming and ask what the meeting should be:

```sql
SELECT b.booking_id, e.email, b.starts_at, b.ends_at, b.source
FROM bookings b JOIN employees e ON e.employee_id = b.employee_id
WHERE b.ends_at <= b.starts_at AND b.starts_at > now()
ORDER BY b.starts_at;
```

3. **Cancel what you cannot correct**, so availability stops lying:

```sql
BEGIN;
UPDATE bookings
   SET status = 'cancelled', updated_at = now()
 WHERE ends_at <= starts_at
   AND starts_at > now()
   AND status <> 'cancelled';
-- Confirm the count matches what you expect, then COMMIT.
COMMIT;
```

Past rows can be left alone or cancelled for tidiness; they no longer affect availability.

## Prevent

Add the constraint the table should always have had, after the backlog is clean:

```sql
ALTER TABLE bookings
  ADD CONSTRAINT bookings_valid_range CHECK (ends_at > starts_at) NOT VALID;

ALTER TABLE bookings VALIDATE CONSTRAINT bookings_valid_range;
```

`NOT VALID` blocks new violations immediately without scanning 10M rows under a lock.

Beyond the constraint: store and compare instants, never local wall-clock times. Every column
here is `timestamptz` for that reason. Timezone conversion belongs at the edges, when rendering
to a user, not in the middle of range arithmetic.

## Escalate

Escalate to the application on-call if:

- rows are still being created now -- the write path is actively broken;
- the count is above 50, or the durations cluster at a whole number of hours, which points at a
  timezone bug that is probably corrupting more than these rows;
- DI002 is also reporting, since inverted ranges make the overlap results unreliable and the two
  need untangling together.
