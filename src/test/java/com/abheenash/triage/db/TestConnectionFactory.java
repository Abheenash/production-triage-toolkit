package com.abheenash.triage.db;

import java.util.function.UnaryOperator;

/**
 * Reaches the package-private test seam on {@link ConnectionFactory} from other test packages.
 *
 * <p>Exists so that integration tests can supply a password without mutating the JVM environment,
 * while the seam itself stays package-private and invisible to anything outside the build.
 */
public final class TestConnectionFactory {

    private TestConnectionFactory() {
    }

    public static ConnectionFactory withPassword(ConnectionSettings settings, String password) {
        UnaryOperator<String> env = name -> name.equals(settings.passwordEnvVar()) ? password : null;
        return new ConnectionFactory(settings, env);
    }

    /** Exposes the read-only assertion so a test can prove it rejects a writable session. */
    public static void verifyReadOnly(java.sql.Connection conn) throws java.sql.SQLException {
        ConnectionFactory.verifyReadOnly(conn);
    }
}
