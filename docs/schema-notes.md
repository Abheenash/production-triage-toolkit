# Schema notes: the three constraints that are missing on purpose

`db/schema.sql` leaves out three constraints a textbook version of this schema would have. This
page explains why, because "the sample schema is badly designed" is a reasonable first reaction
and the omissions are the point.

## The three

| Missing | Lets this exist | Runbook that prescribes the fix |
|---|---|---|
| `bookings.room_id` foreign key to `rooms` | Orphaned bookings | [DI001](../runbooks/DI001-orphaned-bookings.md) |
| `CHECK (ends_at > starts_at)` on `bookings` | Inverted and zero-length ranges | [DI005](../runbooks/DI005-invalid-time-range.md) |
| `UNIQUE (external_event_id)` on `bookings` | Duplicate calendar events | [DI007](../runbooks/DI007-duplicate-events.md) |

## Why not just add them

Because a schema that cannot drift has nothing to triage, and a tool that only works against
schemas that cannot drift is useless for the job it exists to do.

More importantly, these are not invented weaknesses. They are the three constraints that real
high-write tables most often lack, and each is missing for a reason that sounds sensible when it
happens:

- **The foreign key** was left off for write throughput, or because bookings are inserted before
  rooms are guaranteed to exist during an import, or because a migration added the column and
  nobody came back for the constraint.
- **The range check** was left off because the application validates it, and the application does
  validate it -- in the browser, on the path that has a browser. The API and the importer do not.
- **The unique index** was left off because `external_event_id` is nullable for most rows and
  somebody was unsure whether a partial unique index would work. It does; see DI007.

A production database of any age has a list like this. Finding the drift such a list produces is
exactly what a triage toolkit is for.

## The checks find the drift; the runbooks stop it recurring

Every one of the three runbooks prescribes adding the missing constraint as the permanent fix, and
each explains how to add it without taking a long exclusive lock on a 10-million-row table:

```sql
-- New rows are validated immediately; existing rows are not scanned, so the lock is brief.
ALTER TABLE bookings
  ADD CONSTRAINT bookings_room_id_fkey FOREIGN KEY (room_id) REFERENCES rooms(room_id) NOT VALID;

-- Later, once the backlog is cleaned, without blocking writes.
ALTER TABLE bookings VALIDATE CONSTRAINT bookings_room_id_fkey;
```

That two-step pattern is the actual operational content. Anyone can say "add a foreign key"; doing
it to a hot table in production without an outage is the part worth writing down.

## What the schema does constrain

Everything where a violation would be meaningless rather than instructive:

- `rooms.capacity > 0`
- `bookings.status IN ('confirmed','tentative','cancelled')`
- `bookings.source IN ('web','mobile','calendar_sync')`
- `employees.status IN ('active','terminated','on_leave')`
- `facility_requests.priority IN ('p1','p2','p3')`, and similar for category and status
- foreign keys on `rooms.site_id`, `employees.home_site_id` and `badges.employee_id`

A booking with `status = 'maybe'` would not teach anything about triage; it would just be a bug in
the generator. The three deliberate omissions are the ones that model real drift.

## One more deliberate choice: every timestamp is `timestamptz`

Not `timestamp`. A room booking is an instant in time, and a building in Houston and one in
Denver do not agree about what "09:00" means. `timestamptz` stores the instant and converts at the
edges; `timestamp` stores whatever the writer happened to mean and hopes the reader agrees.

Most of the DI005 findings in a real system come from exactly that disagreement -- see the
timezone-arithmetic section of its runbook -- which is why the schema does not reproduce it.
