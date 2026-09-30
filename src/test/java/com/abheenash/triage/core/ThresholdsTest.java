package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdsTest {

    @Test
    void defaultsCoverEveryPlaceholderTheShippedChecksUse() {
        // If a check ever references a threshold that does not exist, loadSql throws. Proving it
        // here means that failure surfaces in the build rather than at 2am against production.
        for (CheckSpec spec : CheckCatalog.all()) {
            assertThat(CheckCatalog.loadSql(spec, Thresholds.defaults()))
                    .as("check %s has an unresolved placeholder", spec.id())
                    .doesNotContain("${");
        }
    }

    @Test
    void overrideReplacesOnlyTheNamedValue() {
        Thresholds t = Thresholds.defaults().with(List.of("booking_window_days=30"));
        assertThat(t.get("booking_window_days")).isEqualTo("30");
        assertThat(t.get("stuck_job_minutes")).isEqualTo(Thresholds.DEFAULTS.get("stuck_job_minutes"));
    }

    @Test
    void unknownThresholdIsRejectedAndTheMessageListsTheRealOnes() {
        assertThatThrownBy(() -> Thresholds.defaults().with(List.of("bookings_window=30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown threshold")
                .hasMessageContaining("booking_window_days");
    }

    @Test
    void nonNumericValueIsRejectedBeforeItCanReachSql() {
        // This is the substitution-safety boundary. A value that is not a number can never be
        // interpolated into a query, so no threshold can smuggle SQL in.
        assertThatThrownBy(() -> Thresholds.defaults().with(List.of("booking_window_days=7; DROP TABLE bookings")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void malformedOverrideAndNegativeValueAreRejected() {
        assertThatThrownBy(() -> Thresholds.defaults().with(List.of("booking_window_days")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key=value");
        assertThatThrownBy(() -> Thresholds.defaults().with(List.of("booking_window_days=-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    @Test
    void eachFacilitySlaPriorityIsIndependentlyTunable() {
        // OPS003's SLA was a CASE expression in the query, so it was the one check whose
        // thresholds could not be changed without editing SQL. An SLA is exactly the number a
        // different organisation defines differently, so each priority is its own knob.
        Thresholds t = Thresholds.defaults().with(List.of("sla_p2_hours=12"));

        assertThat(t.get("sla_p2_hours")).isEqualTo("12");
        assertThat(t.get("sla_p1_hours")).isEqualTo("4");
        assertThat(t.get("sla_p3_hours")).isEqualTo("72");

        String sql = CheckCatalog.loadSql(CheckCatalog.byId("OPS003").orElseThrow(), t);
        assertThat(sql).contains("WHEN 'p2' THEN 12");
        // The other two must not move when only p2 is overridden -- a CASE arm picking up the
        // wrong substitution would silently re-grade every ticket of another priority.
        assertThat(sql).contains("WHEN 'p1' THEN 4");
        assertThat(sql).contains("ELSE 72");
    }

    @Test
    void theFacilitySlaCannotSmuggleSqlEither() {
        // The SLA knobs reach a CASE arm rather than a WHERE comparison, so they get the same
        // explicit proof the booking window has.
        assertThatThrownBy(() -> Thresholds.defaults().with(List.of("sla_p1_hours=4; DROP TABLE facility_requests")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void substitutionNamesTheCheckAndThePlaceholderWhenOneIsUnknown() {
        assertThatThrownBy(() -> Thresholds.defaults().substitute("SELECT ${nope}", "DI999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DI999")
                .hasMessageContaining("nope");
    }
}
