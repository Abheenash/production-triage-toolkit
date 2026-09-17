package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckSpecTest {

    private static CheckSpec spec(Severity base, int escalateAt) {
        return new CheckSpec("T001", "title", CheckGroup.OPERATIONS, base, escalateAt,
                "/checks/sql/x.sql", "runbooks/x.md", "impact");
    }

    @Test
    void severityRisesOnceTheRowCountCrossesTheThreshold() {
        CheckSpec s = spec(Severity.MEDIUM, 100);
        assertThat(s.severityFor(1)).isEqualTo(Severity.MEDIUM);
        assertThat(s.severityFor(99)).isEqualTo(Severity.MEDIUM);
        assertThat(s.severityFor(100)).isEqualTo(Severity.HIGH);
        assertThat(s.severityFor(10_000)).isEqualTo(Severity.HIGH);
    }

    @Test
    void aNegativeThresholdDisablesEscalation() {
        CheckSpec s = spec(Severity.HIGH, -1);
        assertThat(s.severityFor(1_000_000)).isEqualTo(Severity.HIGH);
    }

    @Test
    void criticalCannotEscalateFurther() {
        assertThat(spec(Severity.CRITICAL, 5).severityFor(500)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void malformedSpecsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new CheckSpec("", "t", CheckGroup.OPERATIONS, Severity.LOW, -1,
                "/a.sql", "runbooks/a.md", "i")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckSpec("T", "t", CheckGroup.OPERATIONS, Severity.LOW, -1,
                "/a.txt", "runbooks/a.md", "i")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CheckSpec("T", "t", CheckGroup.OPERATIONS, Severity.LOW, -1,
                "/a.sql", "runbooks/a.txt", "i")).isInstanceOf(IllegalArgumentException.class);
    }
}
