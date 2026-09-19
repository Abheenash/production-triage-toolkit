package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.RunDiff;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable output, for the "fits into automation" half of the brief.
 *
 * <p>Shape is stable and flat enough to be useful without a schema: a scheduled run can pipe this
 * into a log aggregator, a CI job can assert on {@code summary.findings}, and an alert can be built
 * from {@code findings[].severity} and {@code findings[].runbook} without the alert author ever
 * reading this code.
 *
 * <p>Two decisions worth stating. Passed checks are included, not omitted -- "DI003 ran and found
 * nothing" is a different and much more valuable fact than DI003 being absent because it crashed.
 * And {@code thresholds} is echoed back, because a finding count means nothing six months later
 * unless you know what the check was comparing against.
 */
public final class JsonReporter implements Reporter {

    private static final String VERSION = "1.0.0";

    private final ObjectMapper mapper;

    public JsonReporter(boolean pretty) {
        this.mapper = new ObjectMapper();
        if (pretty) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
    }

    @Override
    public void write(RunReport report, PrintStream out) {
        try {
            out.println(mapper.writeValueAsString(toMap(report)));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise the report as JSON", e);
        }
    }

    @Override
    public void write(RunReport report, RunDiff diff, PrintStream out) {
        try {
            Map<String, Object> root = toMap(report);
            root.put("comparison", comparison(diff));
            out.println(mapper.writeValueAsString(root));
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise the report as JSON", e);
        }
    }

    Map<String, Object> comparison(RunDiff diff) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("previousStartedAt", diff.previousStartedAt());
        c.put("previousTarget", diff.previousTarget());
        c.put("regressions", diff.hasRegressions());
        c.put("selectionsDiffer", diff.selectionsDiffer());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RunDiff.Change ch : RunDiff.Change.values()) {
            counts.put(ch.name(), diff.of(ch).size());
        }
        c.put("counts", counts);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (RunDiff.Entry e : diff.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checkId", e.checkId());
            m.put("change", e.change().name());
            m.put("previousCount", e.previousCount() < 0 ? null : e.previousCount());
            m.put("currentCount", e.currentCount() < 0 ? null : e.currentCount());
            m.put("delta", e.delta());
            changes.add(m);
        }
        c.put("changes", changes);
        return c;
    }

    Map<String, Object> toMap(RunReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool", "production-triage-toolkit");
        root.put("version", VERSION);
        root.put("startedAt", report.startedAt().toString());
        root.put("durationMs", report.duration().toMillis());
        root.put("exitCode", report.exitCode());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("host", report.target().host());
        target.put("port", report.target().port());
        target.put("database", report.target().database());
        target.put("user", report.target().user());
        target.put("serverVersion", report.target().serverVersion());
        target.put("applicationName", report.target().applicationName());
        root.put("target", target);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checksRun", report.outcomes().size());
        summary.put("passed", report.passed().size());
        summary.put("findings", report.findings().size());
        summary.put("couldNotRun", report.failures().size());
        summary.put("totalMatchedRows", report.totalMatchedRows());
        summary.put("failOn", report.failOn().name());

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<Severity, Integer> counts = report.countsBySeverity();
        for (Severity s : Severity.values()) {
            bySeverity.put(s.name(), counts.getOrDefault(s, 0));
        }
        summary.put("bySeverity", bySeverity);
        root.put("summary", summary);

        root.put("thresholds", report.thresholds().asMap());

        List<Map<String, Object>> findings = new ArrayList<>();
        for (CheckOutcome o : report.findings()) {
            Map<String, Object> f = base(o);
            f.put("severity", o.severity().name());
            f.put("matchCount", o.matchCount());
            f.put("userImpact", o.spec().userImpact());
            f.put("runbook", o.spec().runbook());
            f.put("columns", o.columns());
            f.put("sampleRows", o.sampleRows());
            f.put("sampleTruncated", o.matchCount() > o.sampleRows().size());
            findings.add(f);
        }
        root.put("findings", findings);

        List<Map<String, Object>> passed = new ArrayList<>();
        for (CheckOutcome o : report.passed()) {
            passed.add(base(o));
        }
        root.put("passed", passed);

        List<Map<String, Object>> failures = new ArrayList<>();
        for (CheckOutcome o : report.failures()) {
            Map<String, Object> f = base(o);
            f.put("error", o.errorMessage());
            failures.add(f);
        }
        root.put("couldNotRun", failures);

        return root;
    }

    private static Map<String, Object> base(CheckOutcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkId", o.spec().id());
        m.put("title", o.spec().title());
        m.put("group", o.spec().group().cliName());
        m.put("status", o.status().name());
        m.put("durationMs", o.durationMs());
        return m;
    }
}
