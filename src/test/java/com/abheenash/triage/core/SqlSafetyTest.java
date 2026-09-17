package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyTest {

    @Test
    void everyShippedCheckPassesTheReadOnlyGuard() {
        // The guard that matters most: a check that could write cannot be released, because this
        // test fails the build before it ships.
        for (CheckSpec spec : CheckCatalog.all()) {
            assertThatCode(() -> CheckCatalog.loadSql(spec, Thresholds.defaults()))
                    .as("check %s", spec.id())
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM bookings",
            "UPDATE bookings SET status = 'cancelled'",
            "INSERT INTO bookings (room_id) VALUES (1)",
            "TRUNCATE bookings",
            "DROP TABLE bookings",
            "ALTER TABLE bookings ADD COLUMN x int",
            "GRANT ALL ON bookings TO public",
            "VACUUM FULL bookings"})
    void obviousWritesAreRejected(String sql) {
        assertThatThrownBy(() -> SqlSafety.assertReadOnly(sql, "TEST"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dataModifyingCteIsRejectedEvenThoughItStartsWithWith() {
        // The shape that would slip past a naive "does it start with SELECT?" test.
        String sql = "WITH gone AS (DELETE FROM bookings WHERE booking_id = 1 RETURNING *) "
                + "SELECT count(*) FROM gone";
        assertThatThrownBy(() -> SqlSafety.assertReadOnly(sql, "TEST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELETE");
    }

    @Test
    void functionsThatActOnTheServerAreRejectedEvenThoughTheyWriteNoRows() {
        assertThatThrownBy(() -> SqlSafety.assertReadOnly(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity", "TEST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pg_terminate_backend");
        assertThatThrownBy(() -> SqlSafety.assertReadOnly("SELECT pg_sleep(30)", "TEST"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleStatementsAreRejected() {
        assertThatThrownBy(() -> SqlSafety.assertReadOnly("SELECT 1; SELECT 2", "TEST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single statement");
    }

    @Test
    void aForbiddenWordInsideACommentIsNotAFalsePositive() {
        String sql = "-- this check exists because an UPDATE went wrong\nSELECT 1";
        assertThatCode(() -> SqlSafety.assertReadOnly(sql, "TEST")).doesNotThrowAnyException();
    }

    @Test
    void aForbiddenWordInsideAStringLiteralIsNotAFalsePositive() {
        assertThatCode(() -> SqlSafety.assertReadOnly(
                "SELECT 1 WHERE 'delete me' <> ''", "TEST")).doesNotThrowAnyException();
    }

    @Test
    void hidingAWriteBehindACommentMarkerStillFails() {
        // Stripping comments must not become a way to smuggle SQL past the scanner: the text
        // after the marker is removed entirely, so what remains is the real statement.
        String sql = "SELECT 1 /* harmless */ ; DELETE FROM bookings";
        assertThatThrownBy(() -> SqlSafety.assertReadOnly(sql, "TEST"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forbiddenWordsMustBeWholeWords() {
        // Real column names in the shipped checks contain forbidden words as substrings:
        // last_autovacuum, n_mod_since_analyze, current_setting, pg_blocking_pids.
        assertThatCode(() -> SqlSafety.assertReadOnly(
                "SELECT last_autovacuum, n_mod_since_analyze, current_setting('x') FROM t", "TEST"))
                .doesNotThrowAnyException();
    }

    @Test
    void queriesMustBeginWithSelectOrWith() {
        assertThatThrownBy(() -> SqlSafety.assertReadOnly("EXPLAIN SELECT 1", "TEST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with SELECT or WITH");
    }
}
