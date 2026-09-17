package com.abheenash.triage.report;

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
 * Tests the text output itself, not just the table helper.
 *
 * <p>This is what a person actually reads during an incident, and until this class existed nothing
 * asserted its content directly -- it was only exercised through the CLI subprocess in CliIT,
 * whose assertions are deliberately loose. A regression that dropped the runbook line, or printed
 * findings in the wrong order, would have survived.
 */
class TextReporterWriteTest {

    /** Built from its code point so this source file contains no literal control bytes. */
    private static final String ESC = String.valueOf((char) 27);

    private static final TargetInfo TARGET =
            new TargetInfo("db.internal", 5432, "bookings", "readonly", "16.2", "triage/1.0.0");

    private static CheckSpec spec(String id, Severity sev, CheckGroup group) {
        return new CheckSpec(id, "title of " + id, group, sev, -1,
                "/checks/sql/x.sql", "runbooks/" + id + "-thing.md", "impact of " + id);
    }

    private static CheckOutcome finding(String id, Severity sev, int count, int samples) {
        return CheckOutcome.finding(spec(id, sev, CheckGroup.DATA_INTEGRITY), count,
                List.of("booking_id", "room_id"),
                java.util.stream.IntStream.range(0, samples)
                        .mapToObj(i -> Map.<String, Object>of("booking_id", 100 + i, "room_id", 7))
                        .toList(),
                12);
    }

    private static String render(RunReport report, boolean colour, boolean verbose) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new TextReporter(colour, verbose).write(report, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static RunReport report(List<CheckOutcome> outcomes, Severity failOn) {
        return new RunReport(TARGET, Instant.parse("2026-01-02T03:04:05Z"), Duration.ofMillis(76),
                outcomes, Thresholds.defaults(), failOn);
    }

    @Test
    void headerNamesTheDatabaseAndSummarisesTheRun() {
        String out = render(report(List.of(
                finding("DI002", Severity.HIGH, 3, 2),
                CheckOutcome.pass(spec("DI005", Severity.HIGH, CheckGroup.DATA_INTEGRITY), 4)),
                Severity.INFO), false, false);

        assertThat(out)
                .contains("readonly@db.internal:5432/bookings")
                .contains("PostgreSQL 16.2")
                .contains("2026-01-02T03:04:05Z")
                .contains("2 run, 1 passed, 1 with findings, 0 could not run")
                .contains("76 ms");
    }

    @Test
    void everyFindingShowsItsImpactAndItsRunbook() {
        // The runbook line is the entire argument of the project. If it ever stops being printed,
        // this test is the thing that says so.
        String out = render(report(List.of(finding("DI003", Severity.CRITICAL, 3, 2)), Severity.INFO),
                false, false);

        assertThat(out)
                .contains("CRITICAL")
                .contains("DI003")
                .contains("title of DI003")
                .contains("Impact   impact of DI003")
                .contains("Runbook  runbooks/DI003-thing.md")
                .contains("3 matching rows, checked in 12 ms");
    }

    @Test
    void findingsArePrintedMostUrgentFirst() {
        String out = render(report(List.of(
                finding("AAA", Severity.MEDIUM, 900, 1),
                finding("BBB", Severity.CRITICAL, 1, 1),
                finding("CCC", Severity.HIGH, 5, 1)), Severity.INFO), false, false);

        assertThat(out.indexOf("BBB")).isLessThan(out.indexOf("CCC"));
        assertThat(out.indexOf("CCC")).isLessThan(out.indexOf("AAA"));
    }

    @Test
    void aTruncatedSampleSaysSoRatherThanImplyingThatIsAllOfIt() {
        String out = render(report(List.of(finding("DI001", Severity.HIGH, 4321, 2)), Severity.INFO),
                false, false);
        assertThat(out).contains("showing 2 of 4321").contains("--sample-rows");
    }

    @Test
    void aSampleThatIsCompleteDoesNotClaimTruncation() {
        String out = render(report(List.of(finding("DI001", Severity.HIGH, 2, 2)), Severity.INFO),
                false, false);
        assertThat(out).doesNotContain("showing");
    }

    @Test
    void oneMatchIsSingular() {
        String out = render(report(List.of(finding("DI001", Severity.HIGH, 1, 1)), Severity.INFO),
                false, false);
        assertThat(out).contains("1 matching row,").doesNotContain("1 matching rows");
    }

    @Test
    void aCleanRunSaysSoPlainlyAndExplainsExitZero() {
        String out = render(report(List.of(
                CheckOutcome.pass(spec("A", Severity.HIGH, CheckGroup.OPERATIONS), 2),
                CheckOutcome.pass(spec("B", Severity.HIGH, CheckGroup.OPERATIONS), 3)),
                Severity.INFO), false, false);

        assertThat(out)
                .contains("No findings. All 2 checks passed.")
                .contains("Exit 0")
                .contains("healthy");
    }

    @Test
    void checksThatCouldNotRunAreCalledOutAndDriveExitTwo() {
        // The most important line in the whole reporter: an incomplete run must not read as a
        // healthy one.
        String out = render(report(List.of(
                CheckOutcome.pass(spec("A", Severity.HIGH, CheckGroup.OPERATIONS), 2),
                CheckOutcome.timeout(spec("DBH003", Severity.CRITICAL, CheckGroup.DATABASE_HEALTH), 5000, 5000)),
                Severity.INFO), false, false);

        assertThat(out)
                .contains("COULD NOT RUN")
                .contains("this run is incomplete")
                .contains("DBH003")
                .contains("TIMEOUT")
                .contains("statement_timeout")
                .contains("Exit 2")
                .contains("treat this result as incomplete");
    }

    @Test
    void passingChecksAreSummarisedByDefaultAndListedWhenVerbose() {
        RunReport r = report(List.of(
                CheckOutcome.pass(spec("DI001", Severity.HIGH, CheckGroup.DATA_INTEGRITY), 2),
                CheckOutcome.pass(spec("DI002", Severity.HIGH, CheckGroup.DATA_INTEGRITY), 3)),
                Severity.INFO);

        assertThat(render(r, false, false)).contains("Passed: DI001, DI002").doesNotContain("title of DI001");
        assertThat(render(r, false, true)).contains("PASSED").contains("title of DI001");
    }

    @Test
    void failOnIsExplainedInTheExitLine() {
        String out = render(report(List.of(finding("DI004", Severity.MEDIUM, 2, 1)), Severity.HIGH),
                false, false);
        // The finding is still shown; only the exit code changes.
        assertThat(out).contains("DI004").contains("Exit 0").contains("nothing at or above HIGH");
    }

    @Test
    void withoutColourThereAreNoEscapeSequencesAtAll() {
        String out = render(report(List.of(finding("DI002", Severity.CRITICAL, 3, 2)), Severity.INFO),
                false, false);
        assertThat(out).doesNotContain(ESC);
    }

    @Test
    void colourIsPresentationOnlyAndNeverChangesWhatIsReported() {
        String coloured = render(report(List.of(finding("DI002", Severity.CRITICAL, 3, 2)), Severity.INFO),
                true, false);
        assertThat(coloured).contains(ESC).contains("CRITICAL");

        String stripped = coloured.replaceAll(ESC + "\\[[0-9;]*m", "");
        String plain = render(report(List.of(finding("DI002", Severity.CRITICAL, 3, 2)), Severity.INFO),
                false, false);
        assertThat(stripped)
                .as("stripping the escapes must give back exactly the uncoloured rendering")
                .isEqualTo(plain);
    }

    @Test
    void sampleValuesAppearInTheTable() {
        String out = render(report(List.of(finding("DI001", Severity.HIGH, 2, 2)), Severity.INFO),
                false, false);
        assertThat(out).contains("booking_id").contains("room_id").contains("100").contains("101");
    }
}
