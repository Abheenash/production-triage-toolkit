package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TriageRunnerUnitTest {

    @Test
    void wrapCountsTheWholeResultButReturnsOnlyASample() {
        String wrapped = TriageRunner.wrap("SELECT 1");
        // MATERIALIZED is what stops PostgreSQL inlining the CTE and running the check twice.
        assertThat(wrapped).contains("AS MATERIALIZED");
        // The count must be over the full CTE, not over the limited sample.
        assertThat(wrapped).contains("(SELECT count(*) FROM __triage_finding) AS __total_count");
        assertThat(wrapped).endsWith("LIMIT ?");
    }

    @Test
    void timestampsBecomeIsoStringsSoTextAndJsonAgree() {
        Object v = TriageRunner.normalise(Timestamp.from(Instant.parse("2026-01-02T03:04:05Z")));
        assertThat(v).isEqualTo("2026-01-02T03:04:05Z");
    }

    @Test
    void numbersBooleansAndStringsSurviveUntouchedForJson() {
        assertThat(TriageRunner.normalise(42)).isEqualTo(42);
        assertThat(TriageRunner.normalise(1.5)).isEqualTo(1.5);
        assertThat(TriageRunner.normalise(true)).isEqualTo(true);
        assertThat(TriageRunner.normalise("x")).isEqualTo("x");
        assertThat(TriageRunner.normalise(null)).isNull();
    }

    @Test
    void anythingElseFallsBackToItsStringForm() {
        assertThat(TriageRunner.normalise(java.sql.Date.valueOf("2026-03-04"))).isEqualTo("2026-03-04");
        assertThat(TriageRunner.normalise(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
    }
}
