package com.abheenash.triage.it;

import com.abheenash.triage.core.CheckCatalog;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.Thresholds;
import com.abheenash.triage.core.TriageRunner;
import com.abheenash.triage.db.ConnectionFactory;
import com.abheenash.triage.db.TestConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that every check can actually REPORT something.
 *
 * <p>The six failure scenarios cover DI001-DI004, OPS001 and OPS003. The remaining nine checks
 * were, until this class existed, only ever asserted to return zero rows against clean data --
 * which a check with a typo in its {@code WHERE} clause would also do, forever, while looking
 * perfectly healthy. That is the exact failure mode the tool's own exit code 2 exists to prevent,
 * and leaving it in the test suite would have been indefensible.
 *
 * <p>Each test here creates the real condition and asserts the check sees it. The five
 * database-health checks are the interesting ones: they read {@code pg_stat_activity} and
 * {@code pg_blocking_pids()}, so the only way to test them is to genuinely saturate, block, or
 * abandon a session. That is what these do, on separate connections, with the disturbance always
 * cleaned up afterwards.
 *
 * <p>Where a check has a threshold, the test lowers it rather than manufacturing a condition
 * extreme enough to trip the shipped default. Waiting 60 seconds to prove DBH002 can see a
 * long-running query would be a slow test that proves the same thing as waiting 0 seconds, and
 * lowering the threshold additionally proves the threshold is wired to the query at all.
 */
class CheckFiresIT extends AbstractDatabaseIT {

    private static final int POLL_TIMEOUT_MS = 15_000;

    /**
     * Tags this class's slow query so it can be found and cancelled unambiguously.
     *
     * <p>Necessary because pg_stat_activity is cluster-wide: SafetyIT also runs a deliberately
     * slow generate_series, and a backend left behind by any other test would otherwise be
     * indistinguishable from this one. Matching on a marker rather than on the query shape is
     * what makes these tests independent of execution order.
     */
    private static final String SLOW_QUERY_MARKER = "triage_it_dbh002_probe";

    private static final String SLOW_QUERY =
            "SELECT count(*) /* " + SLOW_QUERY_MARKER + " */ FROM generate_series(1, 2000000000)";

    private ExecutorService background;

    @BeforeEach
    void freshDatabase() throws Exception {
        cancelStrayProbes();
        seedClean();
        background = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopBackgroundWork() throws Exception {
        background.shutdownNow();
        // shutdownNow interrupts the Java thread but does NOT cancel the statement the server is
        // still executing. Without this, a slow probe outlives its test and poisons the next one.
        cancelStrayProbes();
    }

    private static void cancelStrayProbes() throws SQLException {
        exec("SELECT pg_cancel_backend(pid) FROM pg_stat_activity "
                + "WHERE query LIKE '%' || " + quoted(SLOW_QUERY_MARKER) + " || '%' "
                + "AND pid <> pg_backend_pid()");
    }

    private static String quoted(String literal) {
        return "'" + literal.replace("'", "''") + "'";
    }

    // ------------------------------------------------------------------ helpers

    private CheckOutcome run(String checkId, Thresholds thresholds) throws Exception {
        CheckSpec spec = CheckCatalog.byId(checkId).orElseThrow();
        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            return new TriageRunner(conn, thresholds, 30_000, 5).runOne(spec);
        }
    }

    private CheckOutcome run(String checkId) throws Exception {
        return run(checkId, Thresholds.defaults());
    }

    private static Thresholds with(String... overrides) {
        return Thresholds.defaults().with(List.of(overrides));
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Waits for an asynchronous server-side condition instead of sleeping and hoping. */
    private static void awaitTrue(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private static boolean queryReturnsRow(String sql) {
        try (Connection c = adminConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static void assertFinding(CheckOutcome outcome, int expectedCount) {
        assertThat(outcome.status())
                .as("%s should have reported a finding, but was %s (%s)",
                        outcome.spec().id(), outcome.status(), outcome.errorMessage())
                .isEqualTo(CheckOutcome.Status.FINDING);
        assertThat(outcome.matchCount()).as("%s match count", outcome.spec().id()).isEqualTo(expectedCount);
        assertThat(outcome.sampleRows()).as("%s should carry sample rows", outcome.spec().id()).isNotEmpty();
        assertThat(outcome.severity()).isNotNull();
    }

    private static void assertFires(CheckOutcome outcome) {
        assertThat(outcome.status())
                .as("%s should have reported a finding, but was %s (%s)",
                        outcome.spec().id(), outcome.status(), outcome.errorMessage())
                .isEqualTo(CheckOutcome.Status.FINDING);
        assertThat(outcome.matchCount()).isPositive();
        assertThat(outcome.sampleRows()).isNotEmpty();
    }

    // ------------------------------------------------------------------ data integrity

    @Test
    void di005_reportsBookingsThatEndBeforeTheyStart() throws Exception {
        assertThat(run("DI005").status()).isEqualTo(CheckOutcome.Status.PASS);

        // One inverted, one zero-length: the two shapes the runbook distinguishes.
        exec("""
             UPDATE bookings SET ends_at = starts_at - interval '30 minutes'
             WHERE booking_id = (SELECT min(booking_id) FROM bookings
                                 WHERE starts_at >= now() - interval '2 days')
             """);
        exec("""
             UPDATE bookings SET ends_at = starts_at
             WHERE booking_id = (SELECT min(booking_id) + 1 FROM bookings
                                 WHERE starts_at >= now() - interval '2 days')
             """);

        CheckOutcome outcome = run("DI005");
        assertFinding(outcome, 2);
        assertThat(outcome.columns()).contains("duration_minutes");
        // Sorted by duration ascending, so the most negative -- the worst -- comes first.
        assertThat(((Number) outcome.sampleRows().get(0).get("duration_minutes")).doubleValue())
                .isEqualTo(-30.0);
    }

    @Test
    void di006_reportsFutureBookingsInDecommissionedRooms() throws Exception {
        assertThat(run("DI006").status()).isEqualTo(CheckOutcome.Status.PASS);

        // The seed gives decommissioned rooms only PAST bookings. A future one is the problem.
        exec("""
             INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, attendee_count,
                                   status, source, created_at, updated_at)
             SELECT r.room_id, 1, now() + interval '3 hours', now() + interval '4 hours', 2,
                    'confirmed', 'web', now(), now()
             FROM rooms r WHERE NOT r.is_active ORDER BY r.room_id LIMIT 1
             """);

        CheckOutcome outcome = run("DI006");
        assertFinding(outcome, 1);
        assertThat(outcome.columns()).contains("hours_until_start", "decommissioned_at");
        assertThat(((Number) outcome.sampleRows().get(0).get("hours_until_start")).doubleValue())
                .isBetween(2.5, 3.5);
    }

    @Test
    void di007_reportsDuplicateCalendarEvents() throws Exception {
        assertThat(run("DI007").status()).isEqualTo(CheckOutcome.Status.PASS);

        // A retry after a timeout: the same external event written a second time.
        exec("""
             INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, attendee_count,
                                   status, source, external_event_id, created_at, updated_at)
             SELECT b.room_id, b.employee_id, b.starts_at, b.ends_at, b.attendee_count,
                    b.status, b.source, b.external_event_id, now(), now()
             FROM bookings b
             WHERE b.external_event_id IS NOT NULL
               AND b.status <> 'cancelled'
               AND b.starts_at >= now() - interval '2 days'
             ORDER BY b.booking_id
             LIMIT 2
             """);

        CheckOutcome outcome = run("DI007");
        assertFinding(outcome, 2);
        assertThat(outcome.columns()).contains("booking_count", "distinct_rooms");
        assertThat(((Number) outcome.sampleRows().get(0).get("booking_count")).intValue()).isEqualTo(2);
    }

    @Test
    void ops002_reportsWhenNothingHasSucceededRecentlyEnough() throws Exception {
        assertThat(run("OPS002").status()).isEqualTo(CheckOutcome.Status.PASS);

        // Push every success well outside the staleness window. Nothing is left running, so
        // OPS001 must stay quiet -- the two checks cover different silences.
        exec("UPDATE sync_job_runs SET started_at = started_at - interval '48 hours', "
                + "finished_at = finished_at - interval '48 hours'");

        CheckOutcome outcome = run("OPS002");
        assertFinding(outcome, 1);
        assertThat(outcome.columns()).contains("hours_since_success", "failed_runs_24h");
        assertThat(((Number) outcome.sampleRows().get(0).get("hours_since_success")).doubleValue())
                .isGreaterThan(6.0);

        assertThat(run("OPS001").status())
                .as("a stale job is not a stuck job; OPS001 must not fire here")
                .isEqualTo(CheckOutcome.Status.PASS);
    }

    @Test
    void di002_reportsOneRowPerLaterBookingNotOnePerPair() throws Exception {
        // Pins the semantics the DI002 runbook documents. The check was originally a self-join
        // emitting one row per unordered pair; rewriting it as a window function for speed changed
        // that to one row per booking that starts into an earlier one, and the runbook had to be
        // corrected. This stops the two drifting apart again.
        //
        // Three mutually overlapping bookings therefore produce TWO rows, not three: only two of
        // them started while something else was already running. Placed 30 days out, where the
        // seeded grid has nothing, so no existing booking joins the clash.
        exec("""
             INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, attendee_count,
                                   status, source, created_at, updated_at)
             VALUES
               (1, 1, now() + interval '30 days',                  now() + interval '30 days 3 hours', 2, 'confirmed', 'web', now(), now()),
               (1, 2, now() + interval '30 days 1 hour',           now() + interval '30 days 2 hours', 2, 'confirmed', 'web', now(), now()),
               (1, 3, now() + interval '30 days 1 hour 30 minutes',now() + interval '30 days 4 hours', 2, 'confirmed', 'web', now(), now())
             """);

        CheckOutcome outcome = run("DI002");
        assertFinding(outcome, 2);

        // The same booking is what both others collided with, which is what the runbook tells the
        // reader to look for when deciding "one room booked over three times" vs "three clashes".
        var partners = outcome.sampleRows().stream().map(r -> r.get("booking_id_a")).distinct().toList();
        assertThat(partners).as("booking_id_a should be the one booking everything else ran into")
                .hasSize(1);

        var later = outcome.sampleRows().stream().map(r -> r.get("booking_id_b")).distinct().toList();
        assertThat(later).as("each row should name a different later booking").hasSize(2);
        assertThat(outcome.columns()).contains("overlap_minutes", "overlap_starts_at");
    }

    // ------------------------------------------------------------------ database health

    @Test
    void dbh001_reportsWhenConnectionUseCrossesTheThreshold() throws Exception {
        // At the shipped 80% this is quiet on an idle server, which is correct. Dropping the
        // threshold to zero proves both that the query works and that the knob reaches it.
        assertThat(run("DBH001").status()).isEqualTo(CheckOutcome.Status.PASS);

        CheckOutcome outcome = run("DBH001", with("connection_pct=0"));
        assertFires(outcome);
        assertThat(outcome.matchCount()).isEqualTo(1);
        assertThat(outcome.columns()).contains("max_connections", "client_connections", "pct_of_usable_slots");
        assertThat(((Number) outcome.sampleRows().get(0).get("client_connections")).intValue())
                .isPositive();
    }

    @Test
    void dbh002_reportsAQueryThatIsStillRunning() throws Exception {
        assertThat(run("DBH002").status()).isEqualTo(CheckOutcome.Status.PASS);

        Future<?> slow = background.submit(() -> {
            try (Connection c = adminConnection(); Statement st = c.createStatement()) {
                st.execute(SLOW_QUERY);
            } catch (SQLException ignored) {
                // Cancelled in the finally below; that is the expected ending.
            }
        });

        try {
            awaitTrue("the probe query to appear in pg_stat_activity", this::probeIsRunning);

            // Assert while the probe is verifiably still running. The check and the probe are on
            // different connections, so a bare assert could race the probe ending; re-confirming
            // the precondition on failure distinguishes "the check is broken" from "the probe
            // died", which are very different bugs to chase.
            CheckOutcome outcome = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                if (!probeIsRunning()) {
                    continue;
                }
                outcome = run("DBH002", with("long_query_seconds=0"));
                if (outcome.isFinding()) {
                    break;
                }
            }

            assertThat(probeIsRunning())
                    .as("the probe query stopped running, so this test could not measure DBH002")
                    .isTrue();
            assertThat(outcome).isNotNull();
            assertFires(outcome);
            assertThat(outcome.columns()).contains("running_seconds", "query_snippet", "wait_event_type");
            assertThat(outcome.sampleRows()).anySatisfy(row ->
                    assertThat((String) row.get("query_snippet")).contains(SLOW_QUERY_MARKER));
        } finally {
            cancelStrayProbes();
            slow.cancel(true);
        }
    }

    private boolean probeIsRunning() {
        return queryReturnsRow(
                "SELECT 1 FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid() "
                        + "AND query LIKE '%' || " + quoted(SLOW_QUERY_MARKER) + " || '%'");
    }

    @Test
    void dbh003_namesBothTheBlockedSessionAndTheBlocker() throws Exception {
        assertThat(run("DBH003").status()).isEqualTo(CheckOutcome.Status.PASS);

        try (Connection blocker = adminConnection()) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("UPDATE rooms SET name = name WHERE room_id = 1");
            }

            Future<?> victim = background.submit(() -> {
                try (Connection c = adminConnection(); Statement st = c.createStatement()) {
                    c.setAutoCommit(false);
                    // Blocks until the holder above commits or rolls back.
                    st.execute("UPDATE rooms SET name = name WHERE room_id = 1");
                    c.rollback();
                } catch (SQLException ignored) {
                    // Expected when the test tears the blocker down.
                }
            });

            try {
                awaitTrue("a session to become blocked on a lock", () -> queryReturnsRow(
                        "SELECT 1 FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0"));

                CheckOutcome outcome = run("DBH003");
                assertFires(outcome);
                assertThat(outcome.columns())
                        .contains("blocked_pid", "blocking_pid", "blocked_seconds", "blocking_state");

                var row = outcome.sampleRows().get(0);
                assertThat(row.get("blocked_pid")).isNotNull();
                assertThat(row.get("blocking_pid")).isNotNull();
                assertThat(row.get("blocked_pid")).isNotEqualTo(row.get("blocking_pid"));
            } finally {
                blocker.rollback();
                victim.cancel(true);
            }
        }
    }

    @Test
    void dbh004_reportsDeadTuplesAccumulating() throws Exception {
        assertThat(run("DBH004").status()).isEqualTo(CheckOutcome.Status.PASS);

        // Autovacuum off for this table only, or it may clear the dead rows before the check runs
        // and the test would fail intermittently for the wrong reason.
        exec("ALTER TABLE facility_requests SET (autovacuum_enabled = false)");
        try {
            exec("UPDATE facility_requests SET status = status");
            exec("UPDATE facility_requests SET status = status");

            // pg_stat_user_tables is updated asynchronously, so poll rather than assume.
            awaitTrue("n_dead_tup to be reported for facility_requests", () -> queryReturnsRow(
                    "SELECT 1 FROM pg_stat_user_tables WHERE relname = 'facility_requests' AND n_dead_tup > 0"));

            CheckOutcome outcome = run("DBH004", with("dead_tuples_min=1", "dead_tuple_pct=0"));
            assertFires(outcome);
            assertThat(outcome.columns()).contains("n_dead_tup", "dead_pct", "last_autovacuum");
            assertThat(outcome.sampleRows()).anySatisfy(row ->
                    assertThat((String) row.get("relname")).isEqualTo("facility_requests"));
        } finally {
            exec("ALTER TABLE facility_requests RESET (autovacuum_enabled)");
            exec("VACUUM facility_requests");
        }
    }

    @Test
    void dbh005_reportsASessionLeftIdleInsideATransaction() throws Exception {
        assertThat(run("DBH005").status()).isEqualTo(CheckOutcome.Status.PASS);

        try (Connection idle = adminConnection()) {
            idle.setAutoCommit(false);
            try (Statement st = idle.createStatement()) {
                st.execute("SELECT count(*) FROM rooms");
            }
            // Deliberately neither committed nor rolled back: this is the condition under test.

            awaitTrue("a session to be idle in transaction", () -> queryReturnsRow(
                    "SELECT 1 FROM pg_stat_activity WHERE state = 'idle in transaction'"));

            CheckOutcome outcome = run("DBH005", with("idle_in_transaction_seconds=0"));
            assertFires(outcome);
            assertThat(outcome.columns())
                    .contains("idle_seconds", "transaction_seconds", "last_query", "backend_xmin");
            assertThat(outcome.sampleRows()).anySatisfy(row ->
                    assertThat((String) row.get("state")).startsWith("idle in transaction"));

            idle.rollback();
        }
    }

    // ------------------------------------------------------------------ the whole catalogue

    @Test
    void everyCheckInTheCatalogueIsProvenToFireSomewhere() {
        // A guard against the gap this class was written to close: if a sixteenth check is added
        // without a test that makes it report, this fails and says so by name.
        List<String> byScenario = List.of("DI001", "DI002", "DI003", "DI004", "OPS001", "OPS003");
        List<String> byThisClass = List.of("DI005", "DI006", "DI007", "OPS002",
                "DBH001", "DBH002", "DBH003", "DBH004", "DBH005");

        List<String> covered = java.util.stream.Stream.concat(byScenario.stream(), byThisClass.stream())
                .toList();
        List<String> all = CheckCatalog.all().stream().map(CheckSpec::id).toList();

        assertThat(covered)
                .as("a check that is never proven to fire is indistinguishable from a broken one")
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void cleanDataStillPassesEverythingAfterTheseDisturbances() throws Exception {
        // Guards the tests above: each must leave the database as it found it, or it would
        // silently poison whatever ran next.
        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            var report = new TriageRunner(conn, Thresholds.defaults(), 30_000, 5)
                    .run(CheckCatalog.all(), factory.describe(conn), Severity.INFO);
            assertThat(report.findings()).isEmpty();
            assertThat(report.failures()).isEmpty();
        }
    }
}
