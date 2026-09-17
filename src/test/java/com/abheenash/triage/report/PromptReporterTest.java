package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckCatalog;
import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.TargetInfo;
import com.abheenash.triage.core.Thresholds;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt format is a pure function of the report, which is the point: it can be asserted here
 * with no credentials, no network and no model, exactly like every other output format.
 */
class PromptReporterTest {

    private static final TargetInfo TARGET =
            new TargetInfo("db.internal", 5432, "bookings", "readonly", "16.2", "triage/1.0.0");

    private static CheckSpec real(String id) {
        return CheckCatalog.byId(id).orElseThrow();
    }

    private static CheckOutcome finding(String id, Severity sev, int count) {
        return CheckOutcome.finding(real(id), count, List.of("booking_id", "room_id"),
                List.of(Map.of("booking_id", 991, "room_id", 7),
                        Map.of("booking_id", 992, "room_id", 8)), 11);
    }

    private static String render(List<CheckOutcome> outcomes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new PromptReporter().write(
                new RunReport(TARGET, Instant.parse("2026-01-02T03:04:05Z"), Duration.ofMillis(80),
                        outcomes, Thresholds.defaults(), Severity.INFO),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void groundsTheModelByInliningTheFullRunbook() {
        // The whole purpose: the remedy must come from the runbook, so the runbook has to travel
        // with the question rather than be named and left behind.
        String prompt = render(List.of(finding("DI003", Severity.CRITICAL, 3)));

        assertThat(prompt)
                .contains("### Runbook for DI003 (runbooks/DI003-ghost-badges.md)")
                // Distinctive prose from that runbook, proving the file was actually read in.
                .contains("Deactivation is done in the badge system, not here")
                .contains("## Confirm")
                .contains("## Escalate");
    }

    @Test
    void instructsTheModelNotToInvent() {
        String prompt = render(List.of(finding("DI001", Severity.HIGH, 4)));
        assertThat(prompt)
                .contains("Use ONLY the material below")
                .contains("Do not invent table names")
                .contains("say which specific piece is missing instead of guessing");
    }

    @Test
    void carriesTheFindingsWithSeverityCountsImpactAndSampleRows() {
        // Three rows, which is below DI002's escalation threshold of 25, so the severity stays HIGH.
        String prompt = render(List.of(finding("DI002", Severity.HIGH, 3)));
        assertThat(prompt)
                .contains("DI002  [HIGH]")
                .contains("Matching rows:  3")
                .contains("User impact:")
                .contains("booking_id | room_id")
                .contains("991 | 7");
    }

    @Test
    void namesWhatPassedSoTheModelDoesNotSuggestInvestigatingIt() {
        // Ruling things out is half of triage, and a model that does not know what was already
        // checked will cheerfully suggest checking it.
        String prompt = render(List.of(
                finding("DI002", Severity.HIGH, 2),
                CheckOutcome.pass(real("DBH003"), 3)));
        assertThat(prompt)
                .contains("already ruled out, do not suggest investigating these")
                .contains("DBH003");
    }

    @Test
    void warnsThatConclusionsAreProvisionalWhenACheckCouldNotRun() {
        String prompt = render(List.of(
                finding("DI001", Severity.HIGH, 1),
                CheckOutcome.timeout(real("DBH002"), 5000, 5000)));
        assertThat(prompt)
                .contains("this run is incomplete")
                .contains("cannot be ruled out")
                .contains("DBH002");
    }

    @Test
    void aCleanRunStillProducesUsefulContext() {
        String prompt = render(List.of(CheckOutcome.pass(real("DI001"), 2)));
        assertThat(prompt)
                .contains("None. Every check that ran found nothing.")
                .contains("so you know what has been ruled out");
    }

    @Test
    void echoesTheThresholdsTheChecksWereJudgedAgainst() {
        // A count means nothing without the threshold it was compared to, and a model reasoning
        // about "is 3 a lot?" needs that as much as a person does.
        assertThat(render(List.of(finding("OPS003", Severity.MEDIUM, 3))))
                .contains("booking_window_days")
                .contains("stuck_job_minutes                30");
    }

    @Test
    void severityReflectsVolumeInThePromptToo() {
        // DI002 escalates at 25 rows. The prompt must show the escalated severity, or a model
        // ranking the findings would work them in the wrong order.
        assertThat(render(List.of(finding("DI002", Severity.HIGH, 400))))
                .contains("DI002  [CRITICAL]");
    }

    @Test
    void sampleRowsAreCappedSoOneFindingCannotConsumeTheWholeContextWindow() {
        List<Map<String, Object>> many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> Map.<String, Object>of("booking_id", i, "room_id", i))
                .toList();
        String prompt = render(List.of(
                CheckOutcome.finding(real("DI001"), 5000, List.of("booking_id", "room_id"), many, 9)));

        assertThat(prompt).contains("Sample rows (10 of 5000 matching)");
        assertThat(prompt).doesNotContain("\n49 | 49");
    }

    @Test
    void aMissingRunbookIsDeclaredRatherThanSilentlyOmitted() {
        // The dangerous failure would be a prompt that looks complete but is missing its grounding,
        // because the model would then fill the gap with something plausible and wrong.
        String text = PromptReporter.readRunbook("runbooks/does-not-exist.md");
        assertThat(text)
                .contains("was not found")
                .contains("do not substitute your own remedy");
    }

    @Test
    void realRunbooksResolveFromTheWorkingDirectory() {
        assertThat(PromptReporter.readRunbook("runbooks/DI001-orphaned-bookings.md"))
                .contains("DI001")
                .doesNotContain("was not found");
    }

    @Test
    void everyShippedRunbookCanBeInlined() {
        // If a runbook is renamed without updating the catalogue, every prompt silently loses its
        // grounding. Cheap to check, expensive to miss.
        for (CheckSpec spec : CheckCatalog.all()) {
            assertThat(PromptReporter.readRunbook(spec.runbook()))
                    .as("runbook for %s", spec.id())
                    .doesNotContain("was not found");
        }
    }

    @Test
    void groupIsNamedInFullBecauseIdsAloneMeanNothingToAModel() {
        assertThat(render(List.of(finding("DBH005", Severity.HIGH, 1))))
                .contains("Group:          Database health");
    }

    @Test
    void asksForTheOneThingATriageToolCannotDoItself() {
        // Correlation across findings is the genuinely useful ask: the checks are independent by
        // design, so nothing in the tool knows that a stuck sync causes duplicate events.
        assertThat(render(List.of(finding("OPS001", Severity.CRITICAL, 3))))
                .contains("plausibly a CONSEQUENCE of another");
    }
}
