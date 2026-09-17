-- Removes only the tuning layer from db/indexes.sql. Primary keys and unique constraints stay,
-- because dropping those would change what the queries are allowed to do, not merely how fast
-- they do it. Used by scripts/benchmark.sh for the untuned baseline pass.
--
-- The four indexes that db/indexes.sql documents as deliberately-not-created are dropped here
-- too, so that a database left over from an older revision of that file benchmarks cleanly.
DROP INDEX IF EXISTS bookings_starts_at_idx;
DROP INDEX IF EXISTS bookings_confirmed_room_time_idx;
DROP INDEX IF EXISTS employees_terminated_idx;
DROP INDEX IF EXISTS badge_scans_badge_time_idx;
DROP INDEX IF EXISTS facility_requests_open_idx;

DROP INDEX IF EXISTS bookings_starts_room_idx;
DROP INDEX IF EXISTS bookings_external_event_idx;
DROP INDEX IF EXISTS badges_active_employee_idx;
DROP INDEX IF EXISTS sync_job_runs_name_status_idx;
