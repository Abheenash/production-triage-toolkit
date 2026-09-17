package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.TargetInfo;
import com.abheenash.triage.core.Thresholds;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReporterTest {

    private static CheckSpec spec(String id, Severity sev) {
        return new CheckSpec(id, "title " + id, CheckGroup.DATA_INTEGRITY, sev, -1,
                "/checks/sql/x.sql", "runbooks/" + id + ".md", "impact of " + id);
    }

    private static RunReport report() {
        return new RunReport(
                new TargetInfo("db", 5432, "bookings", "readonly", "16.2", "triage/1.0.0"),
                Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMillis(120),
                List.of(
                        CheckOutcome.finding(spec("DI002", Severity.HIGH), 12, List.of("room_id"),
                                List.of(Map.of("room_id", (Object) 7)), 8),
                        CheckOutcome.pass(spec("DI005", Severity.HIGH), 3),
                        CheckOutcome.error(spec("DBH003", Severity.CRITICAL), 2, "boom")),
                Thresholds.defaults(), Severity.INFO);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryCarriesTheNumbersAnAlertWouldBeBuiltOn() {
        Map<String, Object> json = new JsonReporter(false).toMap(report());
        Map<String, Object> summary = (Map<String, Object>) json.get("summary");

        assertThat(summary).containsEntry("checksRun", 3)
                .containsEntry("passed", 1)
                .containsEntry("findings", 1)
                .containsEntry("couldNotRun", 1)
                .containsEntry("totalMatchedRows", 12L);
        // A check that failed to run drives exit 2, even though a finding is also present.
        assertThat(json).containsEntry("exitCode", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyFindingCarriesItsRunbookSoAnAlertCanLinkStraightToIt() {
        Map<String, Object> json = new JsonReporter(false).toMap(report());
        List<Map<String, Object>> findings = (List<Map<String, Object>>) json.get("findings");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0))
                .containsEntry("checkId", "DI002")
                .containsEntry("severity", "HIGH")
                .containsEntry("matchCount", 12)
                .containsEntry("runbook", "runbooks/DI002.md")
                .containsEntry("sampleTruncated", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passedChecksAreListedRatherThanOmitted() {
        // "DI005 ran and found nothing" and "DI005 is missing from the output" must not look the
        // same to whatever consumes this.
        Map<String, Object> json = new JsonReporter(false).toMap(report());
        assertThat((List<Map<String, Object>>) json.get("passed"))
                .singleElement()
                .satisfies(m -> assertThat(m).containsEntry("checkId", "DI005")
                        .containsEntry("status", "PASS"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thresholdsAreEchoedSoAStoredResultStaysInterpretable() {
        Map<String, Object> json = new JsonReporter(false).toMap(report());
        assertThat((Map<String, String>) json.get("thresholds"))
                .containsEntry("booking_window_days", "7")
                .containsEntry("stuck_job_minutes", "30");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failuresCarryTheirErrorText() {
        Map<String, Object> json = new JsonReporter(false).toMap(report());
        assertThat((List<Map<String, Object>>) json.get("couldNotRun"))
                .singleElement()
                .satisfies(m -> assertThat(m).containsEntry("checkId", "DBH003")
                        .containsEntry("error", "boom"));
    }
}
