package com.abheenash.triage.it;

import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.Thresholds;
import com.abheenash.triage.core.TriageRunner;
import com.abheenash.triage.db.ConnectionFactory;
import com.abheenash.triage.db.TestConnectionFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The safety claims, proven against a real server rather than asserted in a README.
 *
 * <p>"Safe to run against production at any time" is the objective that would do real damage if it
 * were merely believed, so each layer gets a test that tries to break it.
 */
class SafetyIT extends AbstractDatabaseIT {

    private static final String READ_ONLY_TRANSACTION = "25006";
    private static final String QUERY_CANCELED = "57014";

    private ConnectionFactory factory() {
        return TestConnectionFactory.withPassword(settings(), PASSWORD);
    }

    @Test
    void theToolsOwnConnectionCannotInsertUpdateOrDelete() throws Exception {
        try (Connection conn = factory().open(); Statement st = conn.createStatement()) {
            for (String write : List.of(
                    "INSERT INTO bookings (room_id, employee_id, starts_at, ends_at, status, source) "
                            + "VALUES (1, 1, now(), now() + interval '1 hour', 'confirmed', 'web')",
                    "UPDATE bookings SET status = 'cancelled' WHERE booking_id = 1",
                    "DELETE FROM bookings WHERE booking_id = 1",
                    "CREATE TABLE should_not_exist (x int)",
                    "DROP TABLE bookings")) {

                assertThatThrownBy(() -> st.execute(write))
                        .as("statement should have been refused: %s", write)
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState())
                                .isEqualTo(READ_ONLY_TRANSACTION));
                conn.rollback();
            }
        }
    }

    @Test
    void theDataIsByteForByteUnchangedAfterAFullRun() throws Exception {
        seedClean();
        long before = checksum();

        try (Connection conn = factory().open()) {
            new TriageRunner(conn, Thresholds.defaults(), 10_000, 5)
                    .run(com.abheenash.triage.core.CheckCatalog.all(), factory().describe(conn),
                            Severity.INFO);
        }

        assertThat(checksum())
                .as("running every check must not change a single row")
                .isEqualTo(before);
    }

    @Test
    void theSessionRefusesToStartIfTheServerSaysItIsWritable() throws Exception {
        // Fail closed: if the read-only guarantee is not actually in place, the tool must stop
        // rather than continue and hope. Proven by handing the guard a genuinely writable session.
        try (Connection writable = adminConnection()) {
            writable.setAutoCommit(false);
            assertThatThrownBy(() -> TestConnectionFactory.verifyReadOnly(writable))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("transaction_read_only=off");
        }
    }

    @Test
    void aSlowCheckIsCancelledByItsStatementTimeoutAndReportedAsTimeoutNotAsPass() throws Exception {
        CheckSpec slow = new CheckSpec("TEST01", "deliberately slow fixture",
                CheckGroup.DATABASE_HEALTH, Severity.HIGH, -1,
                "/checks/sql/TEST_slow_query.sql", "runbooks/DI001-orphaned-bookings.md",
                "test fixture");

        try (Connection conn = factory().open()) {
            long started = System.currentTimeMillis();
            CheckOutcome outcome =
                    new TriageRunner(conn, Thresholds.defaults(), 250, 5).runOne(slow);
            long elapsed = System.currentTimeMillis() - started;

            assertThat(outcome.status()).isEqualTo(CheckOutcome.Status.TIMEOUT);
            assertThat(outcome.errorMessage()).contains("statement_timeout");
            // The query would run for minutes. Being cancelled promptly is the point.
            assertThat(elapsed).isLessThan(15_000);
            // And it must not be mistaken for a healthy result.
            assertThat(outcome.isFinding()).isFalse();
            assertThat(outcome.didNotRun()).isTrue();
        }
    }

    @Test
    void aTimeoutDoesNotLeakIntoTheNextCheck() throws Exception {
        // SET LOCAL is scoped to the transaction the runner rolls back. If it were a session-level
        // SET, the 250ms limit below would still be in force for every later check.
        CheckSpec slow = new CheckSpec("TEST01", "deliberately slow fixture",
                CheckGroup.DATABASE_HEALTH, Severity.HIGH, -1,
                "/checks/sql/TEST_slow_query.sql", "runbooks/DI001-orphaned-bookings.md",
                "test fixture");

        try (Connection conn = factory().open()) {
            TriageRunner runner = new TriageRunner(conn, Thresholds.defaults(), 250, 5);
            assertThat(runner.runOne(slow).status()).isEqualTo(CheckOutcome.Status.TIMEOUT);

            CheckOutcome after = runner.runOne(
                    com.abheenash.triage.core.CheckCatalog.byId("DI001").orElseThrow());
            assertThat(after.status()).isEqualTo(CheckOutcome.Status.PASS);
        }
    }

    @Test
    void theConnectionIdentifiesItselfInPgStatActivity() throws Exception {
        // A DBA looking at a busy server must be able to see what this is without guessing.
        try (Connection conn = factory().open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).startsWith("production-triage-toolkit/");
        }
    }

    @Test
    void anAbsentPasswordVariableIsAClearErrorNotANullPointer() {
        ConnectionFactory noPassword =
                TestConnectionFactory.withPassword(settings(), null);
        assertThatThrownBy(noPassword::open)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST_PGPASSWORD")
                .hasMessageContaining("--password-env");
    }

    @Test
    void theToolLeavesNoIdleTransactionBehindIt() throws Exception {
        // DBH005 exists to find sessions idle in a transaction. A tool that reported that while
        // doing it itself would be worse than useless.
        try (Connection conn = factory().open()) {
            new TriageRunner(conn, Thresholds.defaults(), 10_000, 5)
                    .run(com.abheenash.triage.core.CheckCatalog.all(), factory().describe(conn),
                            Severity.INFO);

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT state FROM pg_stat_activity WHERE pid = pg_backend_pid()")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotEqualTo("idle in transaction");
            }
        }
    }

    /** A cheap fingerprint of every table the checks can see. */
    private static long checksum() throws Exception {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT (SELECT count(*) FROM bookings) * 31"
                            + " + (SELECT count(*) FROM rooms) * 37"
                            + " + (SELECT count(*) FROM employees) * 41"
                            + " + (SELECT count(*) FROM badges) * 43"
                            + " + (SELECT count(*) FROM badge_scans) * 47"
                            + " + (SELECT count(*) FROM facility_requests) * 53"
                            + " + (SELECT count(*) FROM sync_job_runs) * 59"
                            + " + coalesce((SELECT sum(attendee_count) FROM bookings), 0) * 61"
                            + " + coalesce((SELECT count(*) FROM badges WHERE is_active), 0) * 67")) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
