# DI003 -- Active badge belonging to a terminated employee

**Severity** CRITICAL
**Group** Data integrity
**What the user sees** Nothing. That is exactly the problem.

## What this means

Someone has left the company and their access badge still opens doors. The HR feed that
deactivates badges on termination failed, and nothing about that failure is visible: no error, no
degraded page, no user complaint. The only way anyone finds out is a check like this one, or an
incident.

This is CRITICAL regardless of count, and it does not escalate, because it is already at the top.
One ghost badge is a security incident.

`scans_since_termination` is the field that decides how fast you move. Zero means a badge that
could be used. Non-zero means a badge that is being used by someone who no longer works here.

## Confirm

Establish whether the badge has been used since termination, and where:

```sql
SELECT s.scanned_at, s.result, si.name AS site, si.city
FROM badge_scans s
JOIN badges b ON b.badge_id = s.badge_id
JOIN sites si ON si.site_id = s.site_id
WHERE b.badge_id = :badge_id
  AND s.scanned_at > (SELECT terminated_at FROM employees e WHERE e.employee_id = b.employee_id)
ORDER BY s.scanned_at DESC;
```

Then find out how wide the failure is. One badge is an exception; a cluster with similar
termination dates is a broken feed run:

```sql
SELECT date_trunc('day', e.terminated_at) AS terminated_on,
       count(*) AS badges_still_active
FROM badges b
JOIN employees e ON e.employee_id = b.employee_id
WHERE e.status = 'terminated' AND b.is_active
GROUP BY 1 ORDER BY 1 DESC;
```

If they cluster on one or two dates, the feed failed on those runs and you should check every
other thing that run was responsible for, not just badges.

## Fix

**Deactivation is done in the badge system, not here.** This database is a reflection of it;
changing the row without changing the access-control system leaves the badge working and hides
the problem from this check. Order matters:

1. Deactivate the badge in the physical access-control system. This is the step that actually
   closes the hole; everything else is bookkeeping.
2. Confirm in that system that the badge is refused.
3. Let the HR feed re-sync, which should set `is_active = false` and populate `deactivated_at`.
4. Re-run `triage --check DI003` to confirm it now passes.

Only correct the row by hand if the feed cannot be made to run, and only after step 1:

```sql
BEGIN;
UPDATE badges b
   SET is_active = false,
       deactivated_at = coalesce(b.deactivated_at, e.terminated_at, now())
  FROM employees e
 WHERE e.employee_id = b.employee_id
   AND e.status = 'terminated'
   AND b.is_active
   AND b.badge_id = :badge_id;
COMMIT;
```

If there were scans after termination, raise a security incident regardless of how it is resolved.
Somebody used that badge.

## Prevent

- Alert on the HR feed failing, rather than relying on this check to notice the consequence
  hours or days later. A feed that runs and processes zero terminations on a day when there were
  terminations should page someone.
- Make termination deactivate the badge in the same transaction as the status change, so the two
  cannot diverge.
- Run DI003 on a schedule and alert on any non-zero result. It is the check most worth paging on,
  because nothing else in the system will ever tell you.

## Escalate

Escalate immediately, without waiting for the rest of the triage run, if:

- `scans_since_termination` is greater than zero. That is an active security incident: someone
  who has left the company has physically entered a building. Contact physical security now.
- More than a handful of badges are affected, or they cluster on one termination date -- the feed
  is broken and will keep producing these.
- The termination was involuntary. Route through HR and security together; that combination is
  what these controls exist for.
