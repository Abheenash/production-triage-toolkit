package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.RunDiff;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusReporterTest {

    private static CheckSpec spec(String id, CheckGroup g) {
        return new CheckSpec(id, "title", g, Severity.MEDIUM, -1, "/checks/sql/x.sql", "runbooks/x.md", "impact");
    }

    private static RunReport report() {
        return new RunReport(new TargetInfo("db", 5432, "bookings", "ro", "16.2", "t"), Instant.ofEpochSecond(1_700_000_000L),
                Duration.ofMillis(1234),
                List.of(CheckOutcome.finding(spec("DI002", CheckGroup.DATA_INTEGRITY), 42, List.of("c"), List.of(Map.of("c", (Object) 1)), 526),
                        CheckOutcome.pass(spec("DI001", CheckGroup.DATA_INTEGRITY), 12),
                        CheckOutcome.error(spec("DBH001", CheckGroup.DATABASE_HEALTH), 5, "boom")),
                Thresholds.defaults(), Severity.INFO);
    }

    private static String render(RunDiff diff) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        if (diff == null) {
            new PrometheusReporter().write(report(), ps);
        } else {
            new PrometheusReporter().write(report(), diff, ps);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void exportsRunAndPerCheckSeries() {
        String out = render(null);
        assertThat(out)
                .contains("triage_run_timestamp_seconds{target=\"db:5432/bookings\"} 1700000000")
                .contains("triage_run_duration_seconds{target=\"db:5432/bookings\"} 1.234")
                .contains("triage_run_exit_code{target=\"db:5432/bookings\"} 2")
                .contains("triage_findings{target=\"db:5432/bookings\",severity=\"MEDIUM\"} 1")
                .contains("triage_check_matches{target=\"db:5432/bookings\",check=\"DI002\",group=\"data\",severity=\"MEDIUM\"} 42")
                .contains("triage_check_ran{target=\"db:5432/bookings\",check=\"DI001\",group=\"data\",severity=\"NONE\"} 1")
                .contains("triage_check_matches{target=\"db:5432/bookings\",check=\"DI001\",group=\"data\",severity=\"NONE\"} 0")
                .contains("triage_check_ran{target=\"db:5432/bookings\",check=\"DBH001\",group=\"dbhealth\",severity=\"NONE\"} 0")
                .contains("triage_check_duration_seconds{target=\"db:5432/bookings\",check=\"DI002\",group=\"data\",severity=\"MEDIUM\"} 0.526");
        // no sample rows leak into metrics
        assertThat(out).doesNotContain("sample").doesNotContain("\"c\"");
    }

    @Test
    void everyLineIsValidExpositionFormat() {
        Pattern metric = Pattern.compile("^[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[a-zA-Z_][a-zA-Z0-9_]*=\"[^\"]*\"(,[a-zA-Z_][a-zA-Z0-9_]*=\"[^\"]*\")*\\})? -?[0-9.]+$");
        for (String line : render(null).split("\n")) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            assertThat(metric.matcher(line).matches()).as(line).isTrue();
        }
        // every TYPE declaration precedes its first sample
        String out = render(null);
        assertThat(out.indexOf("# TYPE triage_check_ran gauge")).isLessThan(out.indexOf("triage_check_ran{"));
    }

    @Test
    void comparisonAddsChangeSeries() {
        Map<String, int[]> checks = new LinkedHashMap<>();
        checks.put("DI002", new int[] {0, 0});
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("startedAt", "x");
        prev.put("target", "db:5432/bookings");
        prev.put("checks", checks);
        String out = render(RunDiff.between(prev, report()));
        assertThat(out)
                .contains("triage_change{target=\"db:5432/bookings\",kind=\"NEW\"} 1")
                .contains("triage_change{target=\"db:5432/bookings\",kind=\"RESOLVED\"} 0")
                .contains("triage_regressions{target=\"db:5432/bookings\"} 1");
    }

    @Test
    void labelValuesAreEscaped() {
        assertThat(PrometheusReporter.escape("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\nd");
    }
}
