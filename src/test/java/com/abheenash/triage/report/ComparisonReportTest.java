package com.abheenash.triage.report;

import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.RunDiff;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.TargetInfo;
import com.abheenash.triage.core.Thresholds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonReportTest {

    private static CheckSpec spec(String id) {
        return new CheckSpec(id, "title " + id, CheckGroup.OPERATIONS, Severity.MEDIUM, -1,
                "/checks/sql/x.sql", "runbooks/x.md", "impact");
    }

    private static RunDiff diff() {
        Map<String, int[]> checks = new LinkedHashMap<>();
        checks.put("DI001", new int[] {0, 0});
        checks.put("DI002", new int[] {1, 3});
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("startedAt", "2026-09-18T09:00:00Z");
        prev.put("target", "db:5432/bookings");
        prev.put("checks", checks);
        RunReport now = new RunReport(new TargetInfo("db", 5432, "bookings", "ro", "16.2", "t"),
                Instant.EPOCH, Duration.ofMillis(1),
                List.of(CheckOutcome.finding(spec("DI001"), 2, List.of("c"), List.of(Map.of("c", (Object) 1)), 1),
                        CheckOutcome.finding(spec("DI002"), 9, List.of("c"), List.of(Map.of("c", (Object) 1)), 1)),
                Thresholds.defaults(), Severity.INFO);
        return RunDiff.between(prev, now);
    }

    private static RunReport report() {
        return new RunReport(new TargetInfo("db", 5432, "bookings", "ro", "16.2", "t"), Instant.EPOCH,
                Duration.ofMillis(1), List.of(), Thresholds.defaults(), Severity.INFO);
    }

    private static String render(Reporter r) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        r.write(report(), diff(), new PrintStream(buf, true, StandardCharsets.UTF_8));
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void jsonCarriesAComparisonBlock() throws Exception {
        JsonNode root = new ObjectMapper().readTree(render(new JsonReporter(false)));
        JsonNode c = root.get("comparison");
        assertThat(c.get("previousStartedAt").asText()).isEqualTo("2026-09-18T09:00:00Z");
        assertThat(c.get("regressions").asBoolean()).isTrue();
        assertThat(c.get("counts").get("NEW").asInt()).isEqualTo(1);
        assertThat(c.get("counts").get("WORSENED").asInt()).isEqualTo(1);
        JsonNode first = c.get("changes").get(0);
        assertThat(first.get("checkId").asText()).isEqualTo("DI001");
        assertThat(first.get("change").asText()).isEqualTo("NEW");
        assertThat(first.get("previousCount").asInt()).isZero();
        assertThat(c.get("changes").get(1).get("delta").asInt()).isEqualTo(6);
    }

    @Test
    void textShowsASinceSection() {
        String out = render(new TextReporter(false, false));
        assertThat(out).contains("SINCE 2026-09-18T09:00:00Z")
                .contains("NEW          DI001   2 rows -- was passing")
                .contains("WORSENED     DI002   3 -> 9 rows (+6)");
    }

    @Test
    void promptFormatFallsBackToThePlainReport() {
        String out = render(new PromptReporter());
        assertThat(out).doesNotContain("SINCE");
    }
}
