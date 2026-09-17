package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeverityTest {

    @Test
    void declarationOrderIsRankingOrder() {
        // RunReport sorts findings with compareTo, so this ordering is load-bearing, not cosmetic.
        assertThat(Severity.values())
                .containsExactly(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM,
                        Severity.LOW, Severity.INFO);
    }

    @Test
    void atLeastComparesBySeverityNotByOrdinal() {
        assertThat(Severity.CRITICAL.atLeast(Severity.HIGH)).isTrue();
        assertThat(Severity.HIGH.atLeast(Severity.HIGH)).isTrue();
        assertThat(Severity.MEDIUM.atLeast(Severity.HIGH)).isFalse();
        // The default --fail-on is INFO, which everything must satisfy, or exit 1 never fires.
        assertThat(Severity.INFO.atLeast(Severity.INFO)).isTrue();
    }

    @Test
    void escalateMovesOneStepAndSaturates() {
        assertThat(Severity.MEDIUM.escalate()).isEqualTo(Severity.HIGH);
        assertThat(Severity.HIGH.escalate()).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.CRITICAL.escalate()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void parseIsCaseInsensitiveAndNamesTheValidValuesWhenItFails() {
        assertThat(Severity.parse("high")).isEqualTo(Severity.HIGH);
        assertThat(Severity.parse("  Critical  ")).isEqualTo(Severity.CRITICAL);
        assertThatThrownBy(() -> Severity.parse("urgent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRITICAL");
    }
}
