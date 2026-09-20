package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.RunDiff;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;

import java.io.PrintStream;
import java.util.Locale;

/**
 * Prometheus exposition format, for the node_exporter textfile collector.
 *
 * <p>A cron entry that writes this to {@code /var/lib/node_exporter/textfile/triage.prom} turns
 * every check into a time series: {@code triage_check_matches{check="DI002",severity="CRITICAL"}}
 * can be graphed, and {@code triage_run_exit_code > 0} or {@code triage_check_ran == 0} can be an
 * alert rule instead of a log line somebody greps. Passed checks are exported with a match count of
 * 0 and {@code ran="1"}, and checks that could not run with {@code ran="0"} -- so the absence of a
 * series is never mistaken for health, and a check that stops running is a visible change.
 *
 * <p>Labels are limited to check id, group and severity. Sample rows are deliberately NOT exported:
 * metrics are long-lived and low-cardinality, findings are neither.
 */
public final class PrometheusReporter implements Reporter {

    @Override
    public void write(RunReport report, PrintStream out) {
        String target = escape(report.target().host() + ":" + report.target().port() + "/" + report.target().database());

        out.println("# HELP triage_run_timestamp_seconds Start time of the last run.");
        out.println("# TYPE triage_run_timestamp_seconds gauge");
        out.printf(Locale.ROOT, "triage_run_timestamp_seconds{target=\"%s\"} %d%n", target, report.startedAt().getEpochSecond());

        out.println("# HELP triage_run_duration_seconds Wall-clock duration of the last run.");
        out.println("# TYPE triage_run_duration_seconds gauge");
        out.printf(Locale.ROOT, "triage_run_duration_seconds{target=\"%s\"} %.3f%n", target, report.duration().toMillis() / 1000.0);

        out.println("# HELP triage_run_exit_code 0 healthy, 1 findings at or above --fail-on, 2 a check could not run.");
        out.println("# TYPE triage_run_exit_code gauge");
        out.printf(Locale.ROOT, "triage_run_exit_code{target=\"%s\"} %d%n", target, report.exitCode());

        out.println("# HELP triage_findings Number of checks with findings, by severity.");
        out.println("# TYPE triage_findings gauge");
        for (Severity s : Severity.values()) {
            out.printf(Locale.ROOT, "triage_findings{target=\"%s\",severity=\"%s\"} %d%n", target, s.name(),
                    report.countsBySeverity().getOrDefault(s, 0));
        }

        out.println("# HELP triage_check_ran 1 if the check produced an answer, 0 if it timed out or errored.");
        out.println("# TYPE triage_check_ran gauge");
        out.println("# HELP triage_check_matches Rows matched by the check (0 when it passed or could not run).");
        out.println("# TYPE triage_check_matches gauge");
        out.println("# HELP triage_check_duration_seconds How long the check took.");
        out.println("# TYPE triage_check_duration_seconds gauge");
        for (CheckOutcome o : report.outcomes()) {
            String labels = String.format(Locale.ROOT, "target=\"%s\",check=\"%s\",group=\"%s\",severity=\"%s\"",
                    target, escape(o.spec().id()), escape(o.spec().group().cliName()),
                    o.isFinding() ? o.severity().name() : "NONE");
            out.printf(Locale.ROOT, "triage_check_ran{%s} %d%n", labels, o.didNotRun() ? 0 : 1);
            out.printf(Locale.ROOT, "triage_check_matches{%s} %d%n", labels, o.isFinding() ? o.matchCount() : 0);
            out.printf(Locale.ROOT, "triage_check_duration_seconds{%s} %.3f%n", labels, o.durationMs() / 1000.0);
        }
    }

    @Override
    public void write(RunReport report, RunDiff diff, PrintStream out) {
        write(report, out);
        out.println("# HELP triage_change Checks that changed since the compared run, by kind.");
        out.println("# TYPE triage_change gauge");
        String target = escape(report.target().host() + ":" + report.target().port() + "/" + report.target().database());
        for (RunDiff.Change c : RunDiff.Change.values()) {
            out.printf(Locale.ROOT, "triage_change{target=\"%s\",kind=\"%s\"} %d%n", target, c.name(), diff.of(c).size());
        }
        out.println("# HELP triage_regressions 1 if the compared run had new, worsened or broken checks.");
        out.println("# TYPE triage_regressions gauge");
        out.printf(Locale.ROOT, "triage_regressions{target=\"%s\"} %d%n", target, diff.hasRegressions() ? 1 : 0);
    }

    /** Label values: escape backslash, double quote and newline per the exposition format. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
