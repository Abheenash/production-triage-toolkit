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
    void substitutionNamesTheCheckAndThePlaceholderWhenOneIsUnknown() {
        assertThatThrownBy(() -> Thresholds.defaults().substitute("SELECT ${nope}", "DI999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DI999")
                .hasMessageContaining("nope");
    }
}
