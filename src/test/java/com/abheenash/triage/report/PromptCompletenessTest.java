package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckCatalog;
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
 * Evaluates the prompt on the one thing about it that is actually deterministic: completeness.
 *
 * <p>Judging a prompt by reading model output is subjective, unreproducible, and stops happening
 * after the second week. The claim tested here is narrower and checkable -- <b>for every question
 * the prompt asks, is the material needed to answer it present?</b>
 *
 * <p>If the answer is no, the model must either hallucinate or refuse, and no amount of prompt
 * tuning fixes that. If the answer is yes, output quality becomes a question about the model
 * rather than about this tool. That is the boundary worth defending in a test suite, because it is
 * the half that stays true as models change.
 *
 * <p>Each test below pairs one numbered ask in the prompt with the evidence it obliges the prompt
 * to carry. See docs/genai.md.
 */
class PromptCompletenessTest {

    private static final TargetInfo TARGET =
            new TargetInfo("db.internal", 5432, "bookings", "readonly", "16.2", "triage/1.0.0");

    /**
     * A realistic multi-finding incident: a stuck sync, the duplicate events it tends to cause,
     * and an unrelated ghost badge. Exactly the shape where correlation matters.
     */
    private static String incidentPrompt() {
        List<CheckOutcome> outcomes = List.of(
                CheckOutcome.finding(spec("OPS001"), 3, List.of("run_id", "stuck_reason"),
                        List.of(Map.of("run_id", 1443, "stuck_reason", "never sent a heartbeat")), 4),
                CheckOutcome.finding(spec("DI007"), 12, List.of("external_event_id", "booking_count"),
                        List.of(Map.of("external_event_id", "evt-991", "booking_count", 2)), 290),
                CheckOutcome.finding(spec("DI003"), 1, List.of("badge_id", "scans_since_termination"),
                        List.of(Map.of("badge_id", 25, "scans_since_termination", 4)), 16),
                CheckOutcome.pass(spec("DBH003"), 3),
                CheckOutcome.pass(spec("DI002"), 520));
        return render(outcomes);
    }

    private static CheckSpec spec(String id) {
        return CheckCatalog.byId(id).orElseThrow();
    }

    private static String render(List<CheckOutcome> outcomes) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new PromptReporter().write(
                new RunReport(TARGET, Instant.parse("2026-01-02T03:04:05Z"), Duration.ofMillis(900),
                        outcomes, Thresholds.defaults(), Severity.INFO),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // --------------------------------------------------- ask 1: summarise what is wrong

    @Test
    void carriesEveryFindingsTitleSeverityAndCount() {
        String prompt = incidentPrompt();
        for (String id : List.of("OPS001", "DI007", "DI003")) {
            CheckSpec s = spec(id);
            assertThat(prompt).as("%s title", id).contains(s.title());
            assertThat(prompt).as("%s id and severity", id).containsPattern(id + "\\s+\\[[A-Z]+\\]");
        }
        assertThat(prompt).contains("Matching rows:  3").contains("Matching rows:  12");
    }

    // --------------------------------------------------- ask 2: which one first, and why

    @Test
    void carriesTheUserImpactThatJustifiesOrdering() {
        // "Act on this one first" is unanswerable from severity alone. The consequence to a human
        // is what separates a CRITICAL badge hole from a CRITICAL stuck job.
        String prompt = incidentPrompt();
        for (String id : List.of("OPS001", "DI007", "DI003")) {
            assertThat(prompt).as("%s impact", id).contains(spec(id).userImpact());
        }
    }

    // --------------------------------------------------- ask 3: the first three steps

    @Test
    void carriesTheRunbookSectionsTheStepsMustComeFrom() {
        String prompt = incidentPrompt();
        // One Confirm and one Fix heading per finding, so "three concrete steps from the runbooks"
        // is a promise the prompt can actually keep.
        assertThat(countOccurrences(prompt, "## Confirm")).isEqualTo(3);
        assertThat(countOccurrences(prompt, "## Fix")).isEqualTo(3);
        assertThat(countOccurrences(prompt, "## Prevent")).isEqualTo(3);
        assertThat(countOccurrences(prompt, "## Escalate")).isEqualTo(3);
    }

    @Test
    void carriesTheActualRemedyTextNotJustAPointerToIt() {
        // Distinctive lines from two different runbooks. A model handed a path invents the
        // contents; a model handed the contents quotes them.
        String prompt = incidentPrompt();
        assertThat(prompt).contains("Deactivation is done in the badge system, not here");
        assertThat(prompt).contains("marked failed by on-call");
    }

    // --------------------------------------------------- ask 4: which finding causes another

    @Test
    void putsEveryFindingInOnePromptSoCorrelationIsEvenPossible() {
        // The correlation this tool structurally cannot do: OPS001 (a stuck sync) is the usual
        // cause of DI007 (duplicate events). A model can only propose that if it sees both, with
        // their groups, in the same context.
        String prompt = incidentPrompt();
        assertThat(prompt).contains("OPS001").contains("DI007").contains("DI003");
        assertThat(prompt).contains("Group:          Operations");
        assertThat(prompt).contains("Group:          Data integrity");
        assertThat(prompt).contains("plausibly a CONSEQUENCE of another");

        // The OPS001 runbook explicitly names DI007 as its downstream effect, so the link is
        // available as stated fact rather than as something the model must intuit.
        assertThat(prompt).contains("DI007");
    }

    // --------------------------------------------------- ask 5: how to confirm or rule out

    @Test
    void carriesExecutableConfirmationQueries() {
        // A hypothesis without a falsification test is a guess. The Confirm sections are SQL, and
        // they have to survive into the prompt as SQL.
        String prompt = incidentPrompt();
        assertThat(prompt).contains("```sql");
        assertThat(prompt).contains("FROM badge_scans");
        assertThat(prompt).contains("FROM sync_job_runs");
    }

    @Test
    void carriesTheThresholdsThatMakeCountsInterpretable() {
        // "Is 12 duplicates a lot?" cannot be answered without knowing what the check compared
        // against, any more than by a human.
        assertThat(incidentPrompt())
                .contains("booking_window_days              7")
                .contains("stuck_job_minutes                30");
    }

    // --------------------------------------------------- what must NOT be chased

    @Test
    void statesWhatWasAlreadyRuledOut() {
        String prompt = incidentPrompt();
        assertThat(prompt).contains("already ruled out, do not suggest investigating these");
        assertThat(prompt).contains("DBH003");
        assertThat(prompt).contains("DI002");
    }

    // --------------------------------------------------- confidence

    @Test
    void anIncompleteRunIsDeclaredSoConclusionsAreHedged() {
        String prompt = render(List.of(
                CheckOutcome.finding(spec("DI003"), 1, List.of("badge_id"),
                        List.of(Map.of("badge_id", 25)), 9),
                CheckOutcome.timeout(spec("DBH004"), 5000, 5000)));

        assertThat(prompt)
                .contains("this run is incomplete")
                .contains("cannot be ruled out")
                .contains("DBH004");
    }

    @Test
    void theInstructionsForbidTheTwoFailureModesThatMatterDuringAnIncident() {
        String prompt = incidentPrompt();
        assertThat(prompt).contains("Do not invent");
        assertThat(prompt).contains("say which specific piece is missing instead of guessing");
    }

    // --------------------------------------------------- the guard on the guard

    @Test
    void everyRunbookStillHasTheSectionsThePromptPromises() {
        // The prompt promises "three concrete steps, taken from the runbooks below". If a runbook
        // loses its Confirm or Fix section, the prompt is promising something it no longer
        // carries. This fails by name rather than degrading silently.
        for (CheckSpec s : CheckCatalog.all()) {
            String runbook = PromptReporter.readRunbook(s.runbook());
            assertThat(runbook).as("runbook for %s", s.id())
                    .contains("## Confirm")
                    .contains("## Fix");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
