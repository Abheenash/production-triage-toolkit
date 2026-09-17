package com.abheenash.triage.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionSettingsTest {

    private static ConnectionSettings settings(String host, int port, String db, String user) {
        return new ConnectionSettings(host, port, db, user, "PGPASSWORD", 10, 60, "triage/1.0.0");
    }

    @Test
    void buildsAStandardPostgresJdbcUrl() {
        assertThat(settings("db.internal", 5432, "bookings", "readonly").jdbcUrl())
                .isEqualTo("jdbc:postgresql://db.internal:5432/bookings");
    }

    @Test
    void thereIsNowhereToPutAPassword() {
        // Structural, not behavioural: the type has no password component, so a password cannot be
        // logged or serialised by accident from here. It is read from the environment at connect
        // time and never stored.
        assertThat(ConnectionSettings.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("password")
                .contains("passwordEnvVar");
    }

    @Test
    void blankHostDatabaseOrUserIsRejected() {
        assertThatThrownBy(() -> settings("  ", 5432, "db", "u"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");
        assertThatThrownBy(() -> settings("h", 5432, "", "u"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("database");
        assertThatThrownBy(() -> settings("h", 5432, "db", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user");
    }

    @Test
    void portMustBeInRangeAndTheMessageSaysWhatWasGiven() {
        assertThatThrownBy(() -> settings("h", 0, "db", "u"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("got 0");
        assertThatThrownBy(() -> settings("h", 70000, "db", "u"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("70000");
        assertThatCode(() -> settings("h", 1, "db", "u")).doesNotThrowAnyException();
        assertThatCode(() -> settings("h", 65535, "db", "u")).doesNotThrowAnyException();
    }

}
