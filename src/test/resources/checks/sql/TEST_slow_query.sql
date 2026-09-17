-- Test fixture only -- never in the catalogue, never shipped.
-- Deliberately expensive so that a statement timeout can be observed cancelling it. It does no
-- I/O and touches no table, so it proves the timeout works without depending on how much data
-- happens to be loaded.
SELECT count(*) AS rows_counted
FROM generate_series(1, 2000000000)
