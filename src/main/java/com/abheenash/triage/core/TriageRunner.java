package com.abheenash.triage.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a selection of checks against one connection and assembles the report.
 *
 * <p>Each check runs in its own transaction, which is rolled back whether it succeeded or not.
 * That gives three things: a statement timeout scoped with {@code SET LOCAL} so it cannot leak
 * into the next check, a clean slate after a failure, and -- because the transaction is both
 * read-only and short -- no long-lived snapshot pinning autovacuum. A tool that reports DBH005
 * while itself sitting idle in a transaction would be an embarrassing thing to ship.
 *
 * <p>One check failing does not abort the run. A stuck {@code pg_stat_activity} should not cost
 * you the data-integrity results, so failures are recorded and the run continues.
 */
public final class TriageRunner {

    /** PostgreSQL's SQLSTATE for a statement cancelled by {@code statement_timeout}. */
    private static final String SQLSTATE_QUERY_CANCELED = "57014";

    private final Connection connection;
    private final Thresholds thresholds;
    private final long statementTimeoutMs;
    private final int sampleRows;

    public TriageRunner(Connection connection, Thresholds thresholds, long statementTimeoutMs, int sampleRows) {
        if (statementTimeoutMs < 1) {
            throw new IllegalArgumentException("statement timeout must be at least 1ms");
        }
        if (sampleRows < 0) {
            throw new IllegalArgumentException("sample rows must not be negative");
        }
        this.connection = connection;
        this.thresholds = thresholds;
        this.statementTimeoutMs = statementTimeoutMs;
        this.sampleRows = sampleRows;
    }

    public RunReport run(List<CheckSpec> specs, TargetInfo target, Severity failOn) {
        Instant startedAt = Instant.now();
        long t0 = System.nanoTime();

        List<CheckOutcome> outcomes = new ArrayList<>(specs.size());
        for (CheckSpec spec : specs) {
            outcomes.add(runOne(spec));
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - t0);
        return new RunReport(target, startedAt, elapsed, List.copyOf(outcomes), thresholds, failOn);
    }

    /**
     * Runs a single check. Public because running one diagnostic is a legitimate thing to want --
     * it is what {@code --check DI002} does -- and because it is the seam the safety tests use to
     * prove a slow query gets cancelled.
     */
    public CheckOutcome runOne(CheckSpec spec) {
        long t0 = System.nanoTime();
        try {
            String sql = CheckCatalog.loadSql(spec, thresholds);
            return execute(spec, sql, t0);
        } catch (SQLException e) {
            rollbackQuietly();
            long ms = elapsedMs(t0);
            if (SQLSTATE_QUERY_CANCELED.equals(e.getSQLState())) {
                return CheckOutcome.timeout(spec, ms, statementTimeoutMs);
            }
            return CheckOutcome.error(spec, ms, e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage().strip());
        } catch (RuntimeException e) {
            rollbackQuietly();
            return CheckOutcome.error(spec, elapsedMs(t0), e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage().strip());
        }
    }

    private CheckOutcome execute(CheckSpec spec, String sql, long t0) throws SQLException {
        applyTimeout();

        try (PreparedStatement ps = connection.prepareStatement(wrap(sql))) {
            // Server-side statement_timeout is the real guard. This is a client-side backstop for
            // the case where the server never answers at all -- a dead TCP connection, say -- and
            // is given headroom so that a genuine server-side cancellation reports as a TIMEOUT
            // with its proper SQLSTATE rather than as a client abort.
            ps.setQueryTimeout((int) Math.max(1, (statementTimeoutMs / 1000) + 5));
            ps.setInt(1, sampleRows);

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 2; i <= md.getColumnCount(); i++) {
                    columns.add(md.getColumnLabel(i));
                }

                int total = 0;
                List<Map<String, Object>> samples = new ArrayList<>();
                while (rs.next()) {
                    total = rs.getInt(1);
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 2; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i), normalise(rs.getObject(i)));
                    }
                    samples.add(row);
                }

                long ms = elapsedMs(t0);
                connection.rollback();
                return total == 0
                        ? CheckOutcome.pass(spec, ms)
                        : CheckOutcome.finding(spec, total, columns, samples, ms);
            }
        }
    }

    /**
     * Scopes the timeout to this transaction with {@code SET LOCAL}, so the rollback that ends the
     * check also discards it. A session-level {@code SET} would persist and silently apply to
     * whatever ran next.
     */
    private void applyTimeout() throws SQLException {
        // PostgreSQL will not accept a bind parameter for SET LOCAL, so the value
        // has to be concatenated. It is a long, so it cannot carry SQL — but
        // clamping it here means that is provable at the call site rather than
        // something a reader has to go and check the field's type to be sure of.
        long timeoutMs = Math.max(1L, Math.min(statementTimeoutMs, 3_600_000L));
        try (Statement st = connection.createStatement()) {
            st.execute("SET LOCAL statement_timeout = " + timeoutMs);
            st.execute("SET LOCAL idle_in_transaction_session_timeout = " + (timeoutMs + 5_000));
            // A check is a diagnostic, not a queue: never wait for a lock somebody else holds.
            st.execute("SET LOCAL lock_timeout = 1000");
        }
    }

    /**
     * Wraps a check so one round trip returns both the exact match count and a bounded sample.
     *
     * <p>{@code MATERIALIZED} is not decoration. Without it PostgreSQL may inline the CTE into both
     * references and evaluate the check twice -- doubling the cost of the single most expensive
     * thing the tool does. With it the check runs once, is counted, and is sampled.
     *
     * <p>The count is over the whole result, while {@code LIMIT ?} bounds only what is returned.
     * Reporting "5 rows found" because the sample cap was 5 would be worse than useless during an
     * incident.
     */
    static String wrap(String sql) {
        return "WITH __triage_finding AS MATERIALIZED (\n" + sql + "\n)\n"
                + "SELECT (SELECT count(*) FROM __triage_finding) AS __total_count, __triage_finding.*\n"
                + "FROM __triage_finding\n"
                + "LIMIT ?";
    }

    /**
     * Converts a JDBC value into something a reporter can render and Jackson can serialise without
     * a custom module. Timestamps become ISO-8601 strings so that text and JSON output agree.
     */
    static Object normalise(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        return value.toString();
    }

    private void rollbackQuietly() {
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // Nothing useful to do; the caller is already reporting a failure.
        }
    }

    private static long elapsedMs(long t0) {
        return Math.round((System.nanoTime() - t0) / 1_000_000.0);
    }
}
