# OPS003 -- Facility request past its SLA

**Severity** MEDIUM, escalating to HIGH at 25 rows
**Group** Operations
**What the user sees** A broken room stays broken, and the requester hears nothing.

## What this means

An open or in-progress facility ticket has been open longer than the SLA for its priority:

| Priority | SLA | Typical content |
|---|---|---|
| p1 | 4 hours | Security, safety, a room unusable today |
| p2 | 24 hours | HVAC, AV failures affecting scheduled meetings |
| p3 | 72 hours | Furniture, cosmetic, non-blocking |

The clock runs from `opened_at`, not from acknowledgement, because that is when the clock started
for the person who raised it. A ticket acknowledged in 5 minutes and untouched for three days has
still failed them, and measuring from acknowledgement would hide exactly that case.

`hours_over_sla` is the working order. `acknowledged = false` on a p1 is the worst combination on
the board: urgent, and nobody has even looked.

## Confirm

Distinguish a backlog from a routing failure:

```sql
SELECT priority, category, status, count(*),
       round(avg(EXTRACT(epoch FROM (now() - opened_at)) / 3600.0), 1) AS avg_open_hours
FROM facility_requests
WHERE status IN ('open', 'in_progress')
GROUP BY priority, category, status
ORDER BY priority, count DESC;
```

If breaches concentrate in one `category` or one `site_id`, the work is not reaching a particular
team. If they are spread evenly, the queue is simply under-resourced.

```sql
SELECT site_id, count(*) AS breaching
FROM facility_requests fr
WHERE status IN ('open','in_progress')
  AND now() - opened_at > CASE priority WHEN 'p1' THEN interval '4 hours'
                                        WHEN 'p2' THEN interval '24 hours'
                                        ELSE interval '72 hours' END
GROUP BY site_id ORDER BY breaching DESC;
```

Check whether this is new. A step change points at a specific day -- a team member leaving, a
routing rule changing, a site opening:

```sql
SELECT date_trunc('week', opened_at) AS week,
       count(*) FILTER (WHERE resolved_at IS NOT NULL) AS resolved,
       count(*) FILTER (WHERE resolved_at IS NULL)     AS still_open
FROM facility_requests
WHERE opened_at > now() - interval '90 days'
GROUP BY 1 ORDER BY 1;
```

## Fix

This one is not fixed in the database. The tickets are real work, and the only thing that closes
them is somebody doing it. What this check provides is a correctly ordered list.

1. **Work p1 breaches first, unacknowledged ones before the rest.** Acknowledge each one as you
   pick it up, so two people do not start the same ticket.
2. **Tell the requesters.** A ticket that is late and silent generates a second ticket and a
   complaint. One update stops both.
3. **Check for tickets that are already dead** -- the room was fixed, or retired, and nobody
   closed the ticket. These inflate the queue and hide the real work:

```sql
SELECT fr.request_id, fr.room_id, fr.opened_at, r.is_active, r.decommissioned_at
FROM facility_requests fr
LEFT JOIN rooms r ON r.room_id = fr.room_id
WHERE fr.status IN ('open','in_progress')
  AND (r.room_id IS NULL OR NOT r.is_active);
```

A ticket against a room that no longer exists can be closed immediately. Note it, do not just
delete it.

4. **Do not reprioritise to clear the board.** Downgrading a p1 to make the number go down is the
   one response that guarantees the check stops being useful.

## Prevent

- Alert on approaching breach, not on breach. A p1 at 3 hours is still fixable within SLA; a p1
  at 4 hours has already failed.
- Route by category at creation, so AV work reaches the AV team without a human step.
- Auto-close resolved work. Manual closing is the step most often skipped, and it makes the queue
  look worse than it is.
- Review the SLAs occasionally against what is actually achievable. An SLA nobody has met in six
  months is not a target, it is noise, and this check will be ignored because of it. Adjust with
  `--threshold` once the thresholds are configurable per priority, or change the CASE expression
  in the check.

## Escalate

Escalate to the facilities manager if:

- any p1 is more than 4 hours over SLA and still unacknowledged -- nobody has picked it up;
- more than 25 tickets are breaching, which is a staffing or routing problem rather than a
  backlog somebody can work through today;
- breaches concentrate at one site or in one category, which usually means work is not reaching
  a team at all;
- a `security` category ticket is breaching, regardless of its priority. Route that to security
  directly rather than leaving it in the facilities queue.
