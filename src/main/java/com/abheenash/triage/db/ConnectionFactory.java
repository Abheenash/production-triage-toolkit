package com.abheenash.triage.db;

import com.abheenash.triage.core.TargetInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Opens the one connection a run uses, and refuses to hand it back unless it is provably read-only.
 *
 * <p>Three of the four safety layers are established here:
 *
 * <ol>
 *   <li><b>Session read-only.</b> {@code readOnlyMode=always} plus an explicit
 *       {@code default_transaction_read_only = on}, so every transaction on this connection starts
 *       read-only whether or not the caller remembers to ask.
 *   <li><b>Proof, not assumption.</b> {@link #verifyReadOnly} asks the server
 *       {@code SHOW transaction_read_only} and closes the connection if the answer is anything but
 *       {@code on}. A misconfigured driver, a pooler that rewrote the session, or a future edit to
 *       this class all fail closed rather than quietly granting write access to production.
 *   <li><b>A name in {@code pg_stat_activity}.</b> The DBA looking at a busy server can see
 *       exactly what this connection is and who to ask about it.
 * </ol>
 *
 * <p>The fourth layer, the per-check statement timeout, is applied by the runner.
 */
public final class ConnectionFactory {

    private final ConnectionSettings settings;
    private final UnaryOperator<String> environment;

    public ConnectionFactory(ConnectionSettings settings) {
        this(settings, System::getenv);
    }

    /**
     * Test seam: lets a test supply the password without mutating the JVM's real environment,
     * which cannot be done portably. Production code always uses the public constructor, so the
     * only way a password reaches this class in normal use is still from the environment.
     */
    ConnectionFactory(ConnectionSettings settings, UnaryOperator<String> environment) {
        this.settings = settings;
        this.environment = environment;
    }

    /**
     * @throws SQLException             if the database cannot be reached or is not read-only
     * @throws IllegalStateException    if the password environment variable is unset
     */
    public Connection open() throws SQLException {
        String password = environment.apply(settings.passwordEnvVar());
        if (password == null) {
            throw new IllegalStateException(
                    "environment variable " + settings.passwordEnvVar() + " is not set.\n"
                            + "The password is read only from the environment -- there is no --password option, "
                            + "because a password on the command line is visible to every process on the host.\n"
                            + "  export " + settings.passwordEnvVar() + "='...'    # or use --password-env NAME");
        }

        Properties props = new Properties();
        props.setProperty("user", settings.user());
        props.setProperty("password", password);
        props.setProperty("ApplicationName", settings.applicationName());
        props.setProperty("connectTimeout", String.valueOf(settings.connectTimeoutSeconds()));
        props.setProperty("socketTimeout", String.valueOf(settings.socketTimeoutSeconds()));
        // Layer 1: ask the driver to keep the whole session read-only, not just flag it locally.
        props.setProperty("readOnly", "true");
        props.setProperty("readOnlyMode", "always");
        // Reading pg_stat_activity is the point of five of the checks; never let the driver
        // silently prepare-and-cache in a way that hides the real query text from a DBA.
        props.setProperty("assumeMinServerVersion", "12.0");

        Connection conn = DriverManager.getConnection(settings.jdbcUrl(), props);
        try {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            try (Statement st = conn.createStatement()) {
                st.execute("SET SESSION default_transaction_read_only = on");
            }
            conn.commit();
            verifyReadOnly(conn);
            return conn;
        } catch (SQLException | RuntimeException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // The original failure is the one worth reporting.
            }
            throw e;
        }
    }

    /**
     * Asks the server to confirm the session is read-only, and fails closed if it is not.
     *
     * <p>Deliberately a question to PostgreSQL rather than a check of a local flag: the local flag
     * only records what the driver intended.
     */
    static void verifyReadOnly(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW transaction_read_only")) {
            String value = rs.next() ? rs.getString(1) : null;
            if (!"on".equalsIgnoreCase(value)) {
                throw new SQLException(
                        "refusing to continue: the server reports transaction_read_only=" + value
                                + ", so this session could write. Every check is read-only by design and "
                                + "the tool will not run without that guarantee.");
            }
        } finally {
            conn.rollback();
        }
    }

    /** Reads back what was actually connected to, for the report header. */
    public TargetInfo describe(Connection conn) throws SQLException {
        String version;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW server_version")) {
            version = rs.next() ? rs.getString(1) : "unknown";
        } finally {
            conn.rollback();
        }
        return new TargetInfo(settings.host(), settings.port(), settings.database(), settings.user(),
                version, settings.applicationName());
    }
}
