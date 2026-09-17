package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The output a person reads at 2am.
 *
 * <p>Ordered by what that person needs, in order: is anything on fire, what exactly, and what do I
 * do about it. The runbook path sits directly under each finding rather than in a footnote,
 * because the whole argument of this project is that a finding without a next step is only half an
 * answer.
 *
 * <p>Colour is used for severity only, and only when the output is a terminal. Piping to a file or
 * into grep gets clean text with no escape sequences.
 */
public final class TextReporter implements Reporter {

    private static final int MAX_TABLE_WIDTH = 150;
    private static final int MAX_CELL_WIDTH = 34;
    private static final String VERSION = "1.0.0";

    private final boolean colour;
    private final boolean verbose;

    public TextReporter(boolean colour, boolean verbose) {
        this.colour = colour;
        this.verbose = verbose;
    }

    @Override
    public void write(RunReport report, PrintStream out) {
        List<CheckOutcome> findings = report.findings();
        List<CheckOutcome> failures = report.failures();
        List<CheckOutcome> passed = report.passed();

        out.println();
        out.println(bold("Production Triage Toolkit " + VERSION));
        out.printf("  Target    %s (PostgreSQL %s)%n",
                report.target().describe(), report.target().serverVersion());
        out.printf("  Started   %s%n", report.startedAt().toString());
        out.printf("  Checks    %d run, %d passed, %d with findings, %d could not run%n",
                report.outcomes().size(), passed.size(), findings.size(), failures.size());
        out.printf("  Elapsed   %d ms%n", report.duration().toMillis());

        if (!failures.isEmpty()) {
            out.println();
            out.println(bold("COULD NOT RUN")
                    + "  -- these checks produced no answer, so this run is incomplete");
            for (CheckOutcome o : failures) {
                out.printf("  %-7s %s%n", o.spec().id(), o.spec().title());
                out.printf("          %s: %s%n", o.status(), o.errorMessage());
            }
        }

        if (findings.isEmpty()) {
            out.println();
            out.println("  No findings. All " + passed.size() + " checks passed.");
        } else {
            out.println();
            out.println(bold("FINDINGS") + "  -- most urgent first");
            for (CheckOutcome o : findings) {
                writeFinding(o, out);
            }
        }

        if (verbose && !passed.isEmpty()) {
            out.println();
            out.println(bold("PASSED"));
            for (CheckOutcome o : passed) {
                out.printf("  %-7s %-62s %5d ms%n",
                        o.spec().id(), truncate(o.spec().title(), 62), o.durationMs());
            }
        } else if (!passed.isEmpty()) {
            out.println();
            out.println("  Passed: "
                    + passed.stream().map(o -> o.spec().id()).collect(Collectors.joining(", ")));
        }

        out.println();
        int exit = report.exitCode();
        out.println("  " + bold("Exit " + exit) + "  " + exitMeaning(exit, report.failOn()));
        out.println();
    }

    private void writeFinding(CheckOutcome o, PrintStream out) {
        out.println();
        out.printf("  %s  %-7s %s%n",
                severityLabel(o.severity()), o.spec().id(), bold(o.spec().title()));
        out.printf("            %d matching row%s, checked in %d ms%n",
                o.matchCount(), o.matchCount() == 1 ? "" : "s", o.durationMs());
        out.printf("            Impact   %s%n", o.spec().userImpact());
        out.printf("            Runbook  %s%n", o.spec().runbook());

        if (!o.sampleRows().isEmpty()) {
            out.println();
            for (String line : renderTable(o.columns(), o.sampleRows())) {
                out.println("            " + line);
            }
            if (o.matchCount() > o.sampleRows().size()) {
                out.printf("            (showing %d of %d; raise --sample-rows to see more)%n",
                        o.sampleRows().size(), o.matchCount());
            }
        }
    }

    /** Fixed-width table, trimmed to fit a terminal rather than wrapping into unreadable mush. */
    static List<String> renderTable(List<String> columns, List<Map<String, Object>> rows) {
        List<Integer> widths = new ArrayList<>();
        for (String col : columns) {
            int w = col.length();
            for (Map<String, Object> row : rows) {
                w = Math.max(w, render(row.get(col)).length());
            }
            widths.add(Math.min(w, MAX_CELL_WIDTH));
        }

        // Drop trailing columns that will not fit, and say so, rather than silently mangling them.
        int used = 0;
        int shown = 0;
        for (int i = 0; i < columns.size(); i++) {
            int next = widths.get(i) + 3;
            if (used + next > MAX_TABLE_WIDTH && shown > 0) {
                break;
            }
            used += next;
            shown++;
        }

        List<String> lines = new ArrayList<>();
        StringBuilder header = new StringBuilder();
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            header.append(pad(columns.get(i), widths.get(i))).append("   ");
            rule.append("-".repeat(widths.get(i))).append("   ");
        }
        if (shown < columns.size()) {
            header.append("(+").append(columns.size() - shown).append(" more columns)");
        }
        lines.add(header.toString().stripTrailing());
        lines.add(rule.toString().stripTrailing());

        for (Map<String, Object> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                line.append(pad(render(row.get(columns.get(i))), widths.get(i))).append("   ");
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    private static String render(Object value) {
        return value == null ? "null" : value.toString();
    }

    private static String pad(String s, int width) {
        String t = truncate(s, width);
        return t.length() >= width ? t : t + " ".repeat(width - t.length());
    }

    private static String truncate(String s, int width) {
        if (s == null) {
            return "null";
        }
        return s.length() <= width ? s : s.substring(0, Math.max(1, width - 3)) + "...";
    }

    private static String exitMeaning(int exit, Severity failOn) {
        return switch (exit) {
            case 0 -> "healthy -- nothing at or above " + failOn + " was found";
            case 1 -> "findings at or above " + failOn + " -- work the runbooks above";
            default -> "at least one check could not run; treat this result as incomplete";
        };
    }

    private String severityLabel(Severity s) {
        String text = String.format("%-8s", s.name());
        if (!colour) {
            return text;
        }
        String code = switch (s) {
            case CRITICAL -> Ansi.BOLD_RED;
            case HIGH -> Ansi.RED;
            case MEDIUM -> Ansi.YELLOW;
            case LOW -> Ansi.CYAN;
            case INFO -> Ansi.GREY;
        };
        return code + text + Ansi.RESET;
    }

    private String bold(String s) {
        return colour ? Ansi.BOLD + s + Ansi.RESET : s;
    }
}
