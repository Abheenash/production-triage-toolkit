package com.abheenash.triage.db;

/**
 * Everything needed to reach the target database -- except the password, which is deliberately
 * absent.
 *
 * <p>The password is read from the environment at connect time by {@link ConnectionFactory} and
 * never stored on an object, never logged, and never accepted as a command-line option. A
 * command-line password is visible in {@code ps}, in shell history, and in the process table of
 * every other user on the host; for a tool whose whole purpose is to be pointed at production,
 * that is not an acceptable default and there is no flag to opt into it.
 */
public record ConnectionSettings(
        String host,
        int port,
        String database,
        String user,
        String passwordEnvVar,
        int connectTimeoutSeconds,
        int socketTimeoutSeconds,
        String applicationName) {

    public ConnectionSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, got " + port);
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database must not be blank");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user must not be blank");
        }
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }
}
