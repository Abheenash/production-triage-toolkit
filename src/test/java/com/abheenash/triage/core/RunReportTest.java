package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunReportTest {

    private static final TargetInfo TARGET =
            new TargetInfo("db", 5432, "bookings", "readonly", "16.2", "test");

    private static CheckSpec spec(String id, Severity sev) {
        return new CheckSpec(id, "title " + id, CheckGroup.OPERATIONS, sev, -1,
                "/checks/sql/x.sql", "runbooks/x.md", "impact");
    }

    private static CheckOutcome finding(String id, Severity sev, int count) {
        return CheckOutcome.finding(spec(id, sev), count, List.of("c"),
                List.of(Map.of("c", (Object) 1)), 5);
    }

    private static RunReport report(List<CheckOutcome> outcomes, Severity failOn) {
        return new RunReport(TARGET, Instant.EPOCH, Duration.ofMillis(10), outcomes,
                Thresholds.defaults(), failOn);
    }

    @Test
    void findingsAreRankedBySeverityThenByMatchCount() {
        RunReport r = report(List.of(
                finding("A", Severity.MEDIUM, 900),
                finding("B", Severity.CRITICAL, 1),
                finding("C", Severity.HIGH, 5),
                finding("D", Severity.HIGH, 500)), Severity.INFO);

        assertThat(r.findings().stream().map(o -> o.spec().id()))
                .containsExactly("B", "D", "C", "A");
    }

    @Test
    void equalSeverityAndCountTieBreakOnIdSoOutputIsStableBetweenRuns() {
        RunReport r = report(List.of(
                finding("Z", Severity.HIGH, 3),
                finding("A", Severity.HIGH, 3)), Severity.INFO);
        assertThat(r.findings().stream().map(o -> o.spec().id())).containsExactly("A", "Z");
    }

    @Test
    void cleanRunExitsZero() {
        RunReport r = report(List.of(
                CheckOutcome.pass(spec("A", Severity.HIGH), 1),
                CheckOutcome.pass(spec("B", Severity.HIGH), 1)), Severity.INFO);
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.findings()).isEmpty();
        assertThat(r.passed()).hasSize(2);
    }

    @Test
    void anyFindingExitsOneByDefault() {
        RunReport r = report(List.of(finding("A", Severity.LOW, 1)), Severity.INFO);
        assertThat(r.exitCode()).isEqualTo(1);
    }

    @Test
    void failOnRaisesTheBarWithoutHidingTheFinding() {
        // The finding still appears in the report; only the exit code changes. Suppressing the
        // finding as well would make a quiet exit code indistinguishable from a healthy database.
        RunReport r = report(List.of(finding("A", Severity.MEDIUM, 1)), Severity.HIGH);
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.findings()).hasSize(1);
    }

    @Test
    void aCheckThatCouldNotRunOutranksEveryFinding() {
        // The central honesty rule: a broken check must never be reportable as "1 problem found",
        // because that is indistinguishable from a working check that found one problem.
        RunReport r = report(List.of(
                finding("A", Severity.CRITICAL, 10),
                CheckOutcome.timeout(spec("B", Severity.HIGH), 5000, 5000)), Severity.INFO);
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.failures()).hasSize(1);
    }

    @Test
    void aTimeoutOnAnOtherwiseCleanRunStillExitsTwo() {
        RunReport r = report(List.of(
                CheckOutcome.pass(spec("A", Severity.HIGH), 1),
                CheckOutcome.error(spec("B", Severity.HIGH), 3, "relation does not exist")),
                Severity.INFO);
        assertThat(r.exitCode()).isEqualTo(2);
    }

    @Test
    void countsAndTotalsSummariseOnlyFindings() {
        RunReport r = report(List.of(
                finding("A", Severity.CRITICAL, 10),
                finding("B", Severity.CRITICAL, 5),
                finding("C", Severity.LOW, 2),
                CheckOutcome.pass(spec("D", Severity.HIGH), 1)), Severity.INFO);

        assertThat(r.countsBySeverity())
                .containsEntry(Severity.CRITICAL, 2)
                .containsEntry(Severity.LOW, 1);
        assertThat(r.totalMatchedRows()).isEqualTo(17);
    }
}
