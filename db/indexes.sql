-- Production Triage Toolkit -- the tuning layer.
--
-- Kept in a SEPARATE file from schema.sql on purpose: scripts/benchmark.sh measures a full run
-- with and without this file applied, so the numbers in docs/benchmark.md are a reproducible
-- measurement rather than a claim.
--
-- Every index here was kept because it was MEASURED doing work. The first draft of this file had
-- nine indexes; four of them recorded zero scans across a full run at 10 million bookings and
-- were removed, which returned about 410 MB. An index nothing uses is not free: it is disk, it is
-- memory competing with data in the page cache, and it is write amplification on every INSERT and
-- UPDATE to the hot table. The removed four are listed at the bottom with the reason, so that
-- nobody re-adds them on the assumption they were forgotten.
--
-- Method: reset pg_stat_user_indexes, run one check, read idx_scan. See docs/benchmark.md.

-- Used by DI001, DI002, DI004, DI005, DI007 -- every check that is scoped to the booking window.
-- This is the single highest-value index in the file. It turns each windowed check from a
-- sequential scan of 10M rows into a range scan of the ~910,000 in the last 7 days.
--   DI001 379ms -> 70ms, DI004 395ms -> 77ms, DI005 144ms -> 50ms
CREATE INDEX IF NOT EXISTS bookings_starts_at_idx
    ON bookings (starts_at);

-- Used by DI006, and by DI002's lookup of what a clashing booking collides with.
--   DI006 207ms -> 12ms
--
-- At 329 MB this is the most expensive index here, and it would be hard to justify for DI006
-- alone. It is kept because it is the index the APPLICATION needs anyway: "is room X free between
-- these times?" is the query behind every booking attempt, and it has exactly this shape. A
-- production database running this schema would already have it. If yours does not, and DI006 at
-- 200ms is acceptable, this is the one to drop.
CREATE INDEX IF NOT EXISTS bookings_confirmed_room_time_idx
    ON bookings (room_id, starts_at, ends_at)
    WHERE status = 'confirmed';

-- Used by DI003 to find terminated employees. 40 kB.
CREATE INDEX IF NOT EXISTS employees_terminated_idx
    ON employees (employee_id)
    WHERE status = 'terminated';

-- Used by DI003's last-scan lookup.
--
-- Records ZERO scans on a healthy database, because the lateral runs once per ghost badge and a
-- healthy database has none. It is kept anyway, and this is the interesting case: measured with
-- three ghost badges present, DI003 takes 16ms with this index and 240ms without. It costs
-- nothing when there is nothing wrong, and it stops the check slowing down at precisely the
-- moment it has something to report. Judging it on healthy-database scan counts alone would have
-- deleted it.
CREATE INDEX IF NOT EXISTS badge_scans_badge_time_idx
    ON badge_scans (badge_id, scanned_at DESC);

-- Used by OPS003. Only open work can breach an SLA, so the index carries only open work. 16 kB.
CREATE INDEX IF NOT EXISTS facility_requests_open_idx
    ON facility_requests (priority, opened_at)
    WHERE status IN ('open','in_progress');


-- ---------------------------------------------------------------------------------------------
-- Deliberately NOT created. Each of these was measured at zero scans and removed.
--
--   bookings_starts_room_idx (starts_at, room_id)            301 MB, 0 scans
--       Intended to make DI001's anti-join index-only. The planner prefers bookings_starts_at_idx
--       and a heap fetch, which is cheaper than maintaining a second copy of the same range.
--
--   bookings_external_event_idx (external_event_id, ...)      77 MB, 0 scans
--       Intended for DI007's GROUP BY. DI007 filters on the booking window first, which
--       bookings_starts_at_idx already serves, and then aggregates ~180,000 rows. Adding this
--       index changed DI007 by about 5ms and cost 77 MB.
--
--   badges_active_employee_idx (employee_id) WHERE is_active  440 kB, 0 scans
--       badges is small enough that the planner correctly ignores it.
--
--   sync_job_runs_name_status_idx (job_name, status, ...)     88 kB, 0 scans
--       sync_job_runs holds about 1,440 rows. A sequential scan is the right plan and the
--       planner knows it. OPS001 and OPS002 run in around 12ms without it.
-- ---------------------------------------------------------------------------------------------
