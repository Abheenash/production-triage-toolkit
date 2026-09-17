package com.abheenash.triage.it;

import com.abheenash.triage.db.ConnectionSettings;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A real PostgreSQL, loaded with the real schema and the real generator.
 *
 * <p>A real server rather than an embedded or mocked one, because five of the fifteen checks read
 * {@code pg_stat_activity}, {@code pg_stat_user_tables} and {@code pg_blocking_pids()}. There is
 * nothing meaningful to test about those against a fake -- the entire question is whether the
 * query is correct against a genuine server.
 *
 * <p>The server is supplied from outside rather than started by the test process: by
 * {@code docker compose} locally (see {@code scripts/test.sh}) and by a service container in CI.
 * An earlier version used Testcontainers, which starts a container from inside the JVM; that
 * couples the test suite to the Docker daemon's HTTP API, and it broke outright against Docker 29,
 * whose API the client library could not negotiate. Connection details come from the environment,
 * so the same suite runs against Compose, against CI, or against any PostgreSQL 12+ a developer
 * already has.
 *
 * <p>The tests use their own database, created on demand, so that running them never disturbs the
 * demo data in the sandbox.
 */
public abstract class AbstractDatabaseIT {

    protected static final String HOST = env("TRIAGE_IT_HOST", "localhost");
    protected static final int PORT = Integer.parseInt(env("TRIAGE_IT_PORT", "55432"));
    protected static final String DB = env("TRIAGE_IT_DB", "triage_it");
    protected static final String USER = env("TRIAGE_IT_USER", "triage");
    protected static final String PASSWORD = env("TRIAGE_IT_PASSWORD", "triage_local_dev");

    /** A database that is guaranteed to exist, used only to create the test database. */
    private static final String MAINTENANCE_DB = env("TRIAGE_IT_MAINTENANCE_DB", "postgres");

    protected static final int SEED_BOOKINGS = 20_000;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void prepareDatabase() throws Exception {
        assertServerReachable();
        createTestDatabaseIfAbsent();
        runSqlFile("db/schema.sql");
        runSqlFile("db/generate.sql");
        seedClean();
    }

    /**
     * Fails with instructions rather than skipping.
     *
     * <p>A skipped integration test reports as a green build that proved nothing, which is exactly
     * the failure the toolkit's own exit code 2 exists to prevent. The same standard applies to
     * its test suite.
     */
    private static void assertServerReachable() {
        try (Connection c = DriverManager.getConnection(url(MAINTENANCE_DB), USER, PASSWORD)) {
            c.getMetaData().getDatabaseProductVersion();
        } catch (SQLException e) {
            throw new IllegalStateException(String.format(
                    "No PostgreSQL at %s:%d as user '%s'.%n"
                            + "The integration tests need a real database. Start the sandbox first:%n"
                            + "    ./scripts/sandbox-up.sh%n"
                            + "then run:%n"
                            + "    ./scripts/test.sh%n"
                            + "Or point the suite at your own server with TRIAGE_IT_HOST, TRIAGE_IT_PORT, "
                            + "TRIAGE_IT_USER, TRIAGE_IT_PASSWORD.%n"
                            + "Underlying error: %s", HOST, PORT, USER, e.getMessage()), e);
        }
    }

    private static void createTestDatabaseIfAbsent() throws SQLException {
        try (Connection c = DriverManager.getConnection(url(MAINTENANCE_DB), USER, PASSWORD);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = " + quote(DB))) {
                if (rs.next()) {
                    return;
                }
            }
            // CREATE DATABASE cannot run inside a transaction block, hence a bare statement.
            st.execute("CREATE DATABASE " + quoteIdentifier(DB));
        }
    }

    /** Re-seeds clean data and rebuilds the tuning indexes. Every check passes afterwards. */
    protected static void seedClean() throws Exception {
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT seed_workplace(" + SEED_BOOKINGS + ")");
        }
        runSqlFile("db/indexes.sql");
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("ANALYZE");
        }
    }

    protected static void injectScenario(String number) throws Exception {
        try (var files = Files.list(Path.of("scenarios"))) {
            Path scenario = files
                    .filter(p -> p.getFileName().toString().startsWith(number + "-"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no scenario numbered " + number));
            runSqlFile(scenario.toString());
        }
    }

    protected static void runSqlFile(String relativePath) throws Exception {
        String sql = Files.readString(Path.of(relativePath));
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** A writable connection, used only to set tests up -- never by the toolkit itself. */
    protected static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(url(DB), USER, PASSWORD);
    }

    protected static ConnectionSettings settings() {
        return new ConnectionSettings(HOST, PORT, DB, USER, "TEST_PGPASSWORD", 10, 60,
                "production-triage-toolkit/1.0.0-it");
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    private static String quote(String literal) {
        return "'" + literal.replace("'", "''") + "'";
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
