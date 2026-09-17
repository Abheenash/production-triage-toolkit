-- Production Triage Toolkit -- deterministic data generator.
--
-- seed_workplace(target_bookings) fills the schema with a workplace-booking dataset that
-- is CLEAN: every one of the 15 checks must return zero rows against it. That property is
-- what makes the six injectable failure scenarios meaningful -- a finding after injection
-- can only have come from the injection.
--
-- Two design decisions make "clean" a guarantee rather than a hope:
--
--   1. No random() anywhere. Every value is a pure function of the row ordinal, so the same
--      target_bookings always produces byte-identical data. Reproducible benchmarks need it,
--      and so does a test that asserts an exact finding count.
--
--   2. Bookings are laid on a deterministic (room, time-slot) grid instead of being sampled
--      at random. Room r gets a booking every 4 hours, phase-shifted by r, and every booking
--      is at most 90 minutes long. Two confirmed bookings in one room therefore CANNOT
--      overlap -- DI002 is clean by construction, not by luck. Random timestamps would
--      collide constantly at 10M rows and the "clean baseline" would be worthless.
--
-- Scale-derived shape (n = target_bookings):
--   rooms       = clamp(n / 2000, 200, 2000) active, plus 20 decommissioned
--   employees   = clamp(n / 500, 500, 40000), 4% of them terminated
--   span        = n / (rooms * 6) days, 90% in the past and 10% in the future
--   badge scans = n / 10
--   facility    = clamp(n / 1000, 200, 5000) requests
--   sync runs   = 1440 (one every 30 minutes for the last 30 days)

CREATE OR REPLACE FUNCTION seed_workplace(target_bookings bigint DEFAULT 100000)
RETURNS text
LANGUAGE plpgsql
AS $fn$
DECLARE
    n_rooms        integer;
    n_dead_rooms   constant integer := 20;
    n_employees    integer;
    n_sites        constant integer := 12;
    span_days      numeric;
    base_start     timestamptz;
    n_scans        bigint;
    n_requests     integer;
    active_badges  bigint[];
    started        timestamptz := clock_timestamp();
BEGIN
    IF target_bookings < 1000 THEN
        RAISE EXCEPTION 'target_bookings must be at least 1000, got %', target_bookings;
    END IF;

    n_rooms     := greatest(200, least(2000, (target_bookings / 2000)::integer));
    n_employees := greatest(500, least(40000, (target_bookings / 500)::integer));
    span_days   := ceil(target_bookings::numeric / (n_rooms * 6));
    n_scans     := target_bookings / 10;
    n_requests  := greatest(200, least(5000, (target_bookings / 1000)::integer));

    -- 90% of the span in the past, 10% ahead of now.
    base_start := date_trunc('hour', now()) - make_interval(days => (span_days * 0.9)::integer);

    TRUNCATE badge_scans, facility_requests, bookings, badges, employees, rooms, sites, sync_job_runs
        RESTART IDENTITY CASCADE;

    ------------------------------------------------------------------ sites
    INSERT INTO sites (site_id, name, city, timezone, is_active, created_at)
    SELECT i,
           'Site ' || chr(64 + i),
           (ARRAY['Houston','Austin','Dallas','Denver','Chicago','Atlanta',
                  'Seattle','Boston','Phoenix','Raleigh','San Jose','Columbus'])[i],
           (ARRAY['America/Chicago','America/Chicago','America/Chicago','America/Denver',
                  'America/Chicago','America/New_York','America/Los_Angeles','America/New_York',
                  'America/Phoenix','America/New_York','America/Los_Angeles','America/New_York'])[i],
           true,
           now() - interval '5 years'
    FROM generate_series(1, n_sites) i;

    ------------------------------------------------------------------ rooms
    -- Active rooms: 1 .. n_rooms. Capacity 4..20, so an attendee_count of 1..4 can never
    -- exceed it (DI004 clean by construction).
    INSERT INTO rooms (room_id, site_id, name, floor, capacity, is_active, decommissioned_at)
    SELECT i,
           1 + (i % n_sites),
           'Room ' || (1 + (i % n_sites)) || '-' || lpad(i::text, 4, '0'),
           (1 + (i % 12))::smallint,
           (4 + (i % 17))::smallint,
           true,
           NULL
    FROM generate_series(1, n_rooms) i;

    -- Decommissioned rooms sit ABOVE the active range and receive only past bookings, so
    -- DI006 (confirmed FUTURE booking on a dead room) is clean until a scenario injects one.
    INSERT INTO rooms (room_id, site_id, name, floor, capacity, is_active, decommissioned_at)
    SELECT n_rooms + i,
           1 + (i % n_sites),
           'Room ' || (1 + (i % n_sites)) || '-OLD' || lpad(i::text, 3, '0'),
           (1 + (i % 12))::smallint,
           (4 + (i % 17))::smallint,
           false,
           now() - make_interval(days => 30 + (i * 7))
    FROM generate_series(1, n_dead_rooms) i;

    ------------------------------------------------------------------ employees
    -- Every 25th employee is terminated. Their badge is deactivated in the same pass, which
    -- is exactly the invariant DI003 exists to police.
    INSERT INTO employees (employee_id, full_name, email, home_site_id, status, hired_on, terminated_at)
    SELECT i,
           'Employee ' || i,
           'employee' || i || '@example.invalid',
           1 + (i % n_sites),
           CASE WHEN i % 25 = 0 THEN 'terminated'
                WHEN i % 37 = 0 THEN 'on_leave'
                ELSE 'active' END,
           (current_date - make_interval(days => 200 + mod(abs(hashint8(i)::bigint), 2500)::integer))::date,
           CASE WHEN i % 25 = 0
                THEN now() - make_interval(days => 1 + mod(abs(hashint8(i)::bigint), 400)::integer)
                END
    FROM generate_series(1, n_employees) i;

    ------------------------------------------------------------------ badges
    INSERT INTO badges (badge_id, employee_id, badge_number, is_active, issued_at, deactivated_at)
    SELECT e.employee_id,
           e.employee_id,
           'BDG-' || lpad(e.employee_id::text, 7, '0'),
           e.status <> 'terminated',
           e.hired_on::timestamptz + interval '9 hours',
           e.terminated_at
    FROM employees e;

    ------------------------------------------------------------------ bookings
    -- Grid assignment. For ordinal i:
    --   room   = 1 + (i % n_rooms)
    --   seq    = i / n_rooms                    -- which 4-hour slot for that room
    --   start  = base + seq*4h + (room % 8)*30m -- constant per-room phase, so no self-overlap
    --   length = 30 / 60 / 90 minutes           -- always shorter than the 4-hour stride
    INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, attendee_count,
                          status, source, external_event_id, created_at, updated_at)
    SELECT 1 + (i % n_rooms),
           1 + (i % n_employees),
           st,
           st + make_interval(mins => (30 * (1 + (i % 3)))::integer),
           (1 + (i % 4))::smallint,
           CASE WHEN i % 20 < 17 THEN 'confirmed'
                WHEN i % 20 < 19 THEN 'tentative'
                ELSE 'cancelled' END,
           CASE WHEN i % 5 = 0 THEN 'calendar_sync'
                WHEN i % 5 = 1 THEN 'mobile'
                ELSE 'web' END,
           CASE WHEN i % 5 = 0 THEN 'evt-' || i END,
           st - interval '3 days',
           st - interval '3 days'
    FROM generate_series(0, target_bookings - 1) i
    CROSS JOIN LATERAL (
        SELECT base_start
             + make_interval(hours => 4 * (i / n_rooms)::integer)
             + make_interval(mins  => (30 * ((1 + (i % n_rooms)) % 8))::integer)
    ) AS s(st);

    -- Give the decommissioned rooms a plausible history: re-point a slice of already-past
    -- bookings at them. Past only, so DI006 stays clean.
    UPDATE bookings
       SET room_id = n_rooms + 1 + mod(booking_id, n_dead_rooms)
     WHERE booking_id IN (
             SELECT booking_id FROM bookings
              WHERE starts_at < now() - interval '60 days'
              ORDER BY booking_id
              LIMIT least(2000, target_bookings / 50)
           );

    ------------------------------------------------------------------ badge scans
    -- Scans are drawn only from ACTIVE badges. Letting a deactivated badge produce door
    -- events would put a real security problem in the "clean" baseline, and it would ruin the
    -- scenario-03 demo: DI003 reports scans_since_termination precisely so an operator can tell
    -- "a hole that exists" from "a hole somebody is walking through", and that distinction is
    -- worthless if the seed data has terminated employees scanning in every day.
    --
    -- The active badge ids are collected once into an array and indexed into, rather than
    -- re-queried per row. At a million scans the per-row subquery is far too slow.
    SELECT array_agg(badge_id ORDER BY badge_id) INTO active_badges FROM badges WHERE is_active;

    INSERT INTO badge_scans (badge_id, site_id, scanned_at, result)
    SELECT active_badges[1 + mod(abs(hashint8(i)::bigint), array_length(active_badges, 1))],
           1 + mod(abs(hashint8(i * 7 + 1)::bigint), n_sites),
           now() - make_interval(mins => mod(abs(hashint8(i * 13 + 3)::bigint), 60 * 24 * 90)::integer),
           CASE WHEN mod(abs(hashint8(i * 3 + 5)::bigint), 50) = 0 THEN 'denied' ELSE 'granted' END
    FROM generate_series(1, n_scans) i;

    ------------------------------------------------------------------ facility requests
    -- 90% already resolved (any age). The 10% still open are all opened within the last
    -- 2 hours, which is inside even the p1 4-hour SLA -- OPS003 clean by construction.
    INSERT INTO facility_requests (site_id, room_id, opened_by, category, priority, status,
                                   opened_at, acknowledged_at, resolved_at)
    SELECT 1 + (i % n_sites),
           1 + (i % n_rooms),
           1 + (i % n_employees),
           (ARRAY['av','cleaning','hvac','furniture','security'])[1 + (i % 5)],
           (ARRAY['p1','p2','p3'])[1 + (i % 3)],
           CASE WHEN i % 10 = 0 THEN 'open' ELSE 'resolved' END,
           CASE WHEN i % 10 = 0
                THEN now() - make_interval(mins => 5 + (i % 110))
                ELSE now() - make_interval(days => 3 + (i % 200)) END,
           CASE WHEN i % 10 = 0
                THEN now() - make_interval(mins => 2 + (i % 50))
                ELSE now() - make_interval(days => 3 + (i % 200)) + interval '20 minutes' END,
           CASE WHEN i % 10 <> 0
                THEN now() - make_interval(days => 3 + (i % 200)) + interval '6 hours' END
    FROM generate_series(1, n_requests) i;

    ------------------------------------------------------------------ sync job runs
    -- One run every 30 minutes for 30 days. All succeeded, except a scattering of old
    -- failures that were followed by a success. The newest run finished 5 minutes ago, so
    -- OPS002 (stale) is clean; nothing is left in status='running', so OPS001 is clean.
    INSERT INTO sync_job_runs (job_name, started_at, finished_at, status, heartbeat_at,
                               rows_processed, error_message)
    SELECT 'calendar_sync',
           now() - make_interval(mins => 30 * i + 5),
           now() - make_interval(mins => 30 * i + 5) + interval '45 seconds',
           CASE WHEN i > 12 AND i % 97 = 0 THEN 'failed' ELSE 'succeeded' END,
           now() - make_interval(mins => 30 * i + 5) + interval '40 seconds',
           CASE WHEN i > 12 AND i % 97 = 0 THEN 0 ELSE 400 + mod(abs(hashint8(i)::bigint), 900) END,
           CASE WHEN i > 12 AND i % 97 = 0 THEN 'upstream calendar API returned 503' END
    FROM generate_series(0, 1439) i;

    ANALYZE;

    RETURN format(
        'seeded %s bookings across %s active rooms (+%s decommissioned), %s employees, '
        || '%s badge scans, %s facility requests, 1440 sync runs, spanning %s days; took %s',
        target_bookings, n_rooms, n_dead_rooms, n_employees, n_scans, n_requests, span_days,
        justify_interval(clock_timestamp() - started));
END;
$fn$;

COMMENT ON FUNCTION seed_workplace(bigint) IS
  'Fills the schema with a deterministic, check-clean workplace-booking dataset.';
