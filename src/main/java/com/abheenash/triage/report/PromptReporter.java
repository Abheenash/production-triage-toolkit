package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.RunReport;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Emits the run as a grounded prompt, ready to paste into an assistant.
 *
 * <p>Why this exists, and why it is not an LLM call inside the tool.
 *
 * <p>The slow part of triage is rarely the query. It is the twenty minutes spent reassembling
 * context at 2am: what this check means, which rows matter, what the runbook says to do first, and
 * which other findings are related. An assistant is genuinely good at that reassembly -- but only
 * if it is handed the facts. Asked cold, it invents table names and invents remedies, which during
 * an incident is worse than no help at all.
 *
 * <p>So this format does the grounding rather than the reasoning. It emits the findings, the
 * sample rows, and the full text of each relevant runbook, followed by an instruction to use only
 * that material. Everything the answer should be based on travels with the question.
 *
 * <p><b>The tool deliberately does not call a model itself.</b> Four reasons, and they are the
 * same reasons the checks are read-only:
 *
 * <ol>
 *   <li>A diagnostic pointed at production should have no outbound network dependency. An API
 *       that is slow or down must not make triage slow or down.
 *   <li>Findings contain real row data. Which service that may be sent to is the operator's
 *       decision and their compliance boundary, not a default this tool picks for them.
 *   <li>A model's output is not deterministic, and exit codes 0/1/2 are a contract that cron and
 *       CI depend on. Nothing non-deterministic belongs upstream of them.
 *   <li>It stays testable. This format is a pure function of the report, so it is asserted in the
 *       test suite like everything else, with no credentials and no network.
 * </ol>
 *
 * <p>The result is that the useful half of the idea -- assembling grounded context -- is shipped,
 * and the risky half is left as an explicit choice the operator makes by piping the output
 * somewhere. See docs/genai.md.
 */
public final class PromptReporter implements Reporter {

    /** Sample rows are truncated in the prompt: context is finite, and 200 near-identical rows teach a model nothing 200 times. */
    private static final int MAX_PROMPT_SAMPLE_ROWS = 10;

    @Override
    public void write(RunReport report, PrintStream out) {
        List<CheckOutcome> findings = report.findings();
        List<CheckOutcome> failures = report.failures();

        out.println("You are helping an on-call engineer triage a PostgreSQL database.");
        out.println();
        out.println("Use ONLY the material below. It contains the findings, sample rows, and the");
        out.println("full runbook for each. Do not invent table names, column names, remedies, or");
        out.println("causes that do not appear here. If the material is not enough to answer");
        out.println("something, say which specific piece is missing instead of guessing.");
        out.println();
        out.println("Produce, in this order:");
        out.println("  1. A two-sentence summary of what is wrong right now.");
        out.println("  2. The single finding to act on first, and why that one.");
        out.println("  3. The first three concrete steps, taken from the runbooks below.");
        out.println("  4. Any finding that is plausibly a CONSEQUENCE of another, and which.");
        out.println("  5. What you would need to see to confirm or rule out each hypothesis.");
        out.println();
        out.println("=".repeat(78));
        out.println("RUN CONTEXT");
        out.println("=".repeat(78));
        out.printf("Database:      %s (PostgreSQL %s)%n",
                report.target().describe(), report.target().serverVersion());
        out.printf("Run at:        %s%n", report.startedAt());
        out.printf("Checks run:    %d (%d passed, %d with findings, %d could not run)%n",
                report.outcomes().size(), report.passed().size(), findings.size(), failures.size());
        out.printf("Exit code:     %d%n", report.exitCode());
        out.println();
        out.println("Thresholds these checks were evaluated against:");
        report.thresholds().asMap().forEach((k, v) -> out.printf("  %-32s %s%n", k, v));

        if (!failures.isEmpty()) {
            out.println();
            out.println("=".repeat(78));
            out.println("CHECKS THAT COULD NOT RUN -- this run is incomplete");
            out.println("=".repeat(78));
            out.println("Treat conclusions as provisional: these checks produced no answer, so a");
            out.println("problem they would have caught cannot be ruled out.");
            for (CheckOutcome o : failures) {
                out.printf("  %s (%s): %s -- %s%n",
                        o.spec().id(), o.spec().title(), o.status(), o.errorMessage());
            }
        }

        if (findings.isEmpty()) {
            out.println();
            out.println("=".repeat(78));
            out.println("FINDINGS");
            out.println("=".repeat(78));
            out.println("None. Every check that ran found nothing.");
            out.println();
            out.println("Passing checks, so you know what has been ruled out:");
            for (CheckOutcome o : report.passed()) {
                out.printf("  %-7s %s%n", o.spec().id(), o.spec().title());
            }
            out.println();
            return;
        }

        out.println();
        out.println("=".repeat(78));
        out.printf("FINDINGS (%d), most urgent first%n", findings.size());
        out.println("=".repeat(78));

        for (CheckOutcome o : findings) {
            out.println();
            out.println("-".repeat(78));
            out.printf("%s  [%s]  %s%n", o.spec().id(), o.severity(), o.spec().title());
            out.println("-".repeat(78));
            out.printf("Group:          %s%n", o.spec().group().displayName());
            out.printf("Matching rows:  %d%n", o.matchCount());
            out.printf("User impact:    %s%n", o.spec().userImpact());
            out.println();
            writeSample(o, out);
        }

        out.println();
        out.println("=".repeat(78));
        out.println("RUNBOOKS for the findings above -- your remedies must come from these");
        out.println("=".repeat(78));
        for (CheckOutcome o : findings) {
            out.println();
            out.printf("### Runbook for %s (%s)%n", o.spec().id(), o.spec().runbook());
            out.println();
            out.println(readRunbook(o.spec().runbook()));
        }

        out.println();
        out.println("=".repeat(78));
        out.println("CHECKS THAT PASSED -- already ruled out, do not suggest investigating these");
        out.println("=".repeat(78));
        for (CheckOutcome o : report.passed()) {
            out.printf("  %-7s %s%n", o.spec().id(), o.spec().title());
        }
        out.println();
    }

    private static void writeSample(CheckOutcome outcome, PrintStream out) {
        if (outcome.sampleRows().isEmpty()) {
            out.println("No sample rows were captured.");
            return;
        }
        int shown = Math.min(outcome.sampleRows().size(), MAX_PROMPT_SAMPLE_ROWS);
        out.printf("Sample rows (%d of %d matching):%n", shown, outcome.matchCount());
        out.println(String.join(" | ", outcome.columns()));
        for (Map<String, Object> row : outcome.sampleRows().subList(0, shown)) {
            StringBuilder line = new StringBuilder();
            for (String column : outcome.columns()) {
                if (line.length() > 0) {
                    line.append(" | ");
                }
                Object value = row.get(column);
                line.append(value == null ? "null" : value);
            }
            out.println(line);
        }
    }

    /**
     * Reads a runbook from disk, trying the working directory and then alongside the jar.
     *
     * <p>The second location is what makes this work from the container image, where the runbooks
     * sit next to triage.jar in /app rather than under the caller's working directory.
     *
     * <p>A missing runbook degrades to a clearly-labelled note rather than an exception. Losing
     * the grounding text is a worse prompt; failing the run because a markdown file moved would be
     * a worse tool.
     */
    static String readRunbook(String relativePath) {
        for (Path candidate : candidates(relativePath)) {
            try {
                if (candidate != null && Files.isReadable(candidate)) {
                    return Files.readString(candidate, StandardCharsets.UTF_8).strip();
                }
            } catch (IOException e) {
                // Try the next location.
            }
        }
        return "[Runbook " + relativePath + " was not found next to this tool. Its guidance is "
                + "therefore NOT included below -- do not substitute your own remedy for it; say "
                + "that the runbook is missing.]";
    }

    private static List<Path> candidates(String relativePath) {
        return java.util.Arrays.asList(Path.of(relativePath), besideTheJar(relativePath));
    }

    private static Path besideTheJar(String relativePath) {
        try {
            Path jar = Path.of(PromptReporter.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            return dir == null ? null : dir.resolve(relativePath);
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
