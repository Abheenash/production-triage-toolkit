package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDiffTest {

    private static final TargetInfo TARGET = new TargetInfo("db", 5432, "bookings", "ro", "16.2", "t");

    private static CheckSpec spec(String id) {
        return new CheckSpec(id, "title " + id, CheckGroup.OPERATIONS, Severity.MEDIUM, -1,
                "/checks/sql/x.sql", "runbooks/x.md", "impact");
    }

    private static CheckOutcome finding(String id, int count) {
        return CheckOutcome.finding(spec(id), count, List.of("c"), List.of(Map.of("c", (Object) 1)), 5);
    }

    private static RunReport report(CheckOutcome... outcomes) {
        return new RunReport(TARGET, Instant.EPOCH, Duration.ofMillis(10), List.of(outcomes),
                Thresholds.defaults(), Severity.INFO);
    }

    /** A previous run in the shape readPrevious() produces: id -> {status, count}. */
    private static Map<String, Object> previous(Object... idStatusCount) {
        Map<String, int[]> checks = new LinkedHashMap<>();
        for (int i = 0; i < idStatusCount.length; i += 3) {
            checks.put((String) idStatusCount[i], new int[] {(int) idStatusCount[i + 1], (int) idStatusCount[i + 2]});
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startedAt", "2026-09-18T09:00:00Z");
        m.put("target", "db:5432/bookings");
        m.put("checks", checks);
        return m;
    }

    private static RunDiff.Entry only(RunDiff d, RunDiff.Change c) {
        assertThat(d.of(c)).hasSize(1);
        return d.of(c).get(0);
    }

    @Test
    void everyKindOfMovementIsClassified() {
        RunDiff d = RunDiff.between(
                previous("NEW1", 0, 0, "RES1", 1, 7, "WOR1", 1, 3, "IMP1", 1, 9, "SAME", 1, 4,
                        "BRK1", 0, 0, "REC1", 2, -1, "QUIET", 0, 0, "GONE", 1, 2),
                report(finding("NEW1", 5), CheckOutcome.pass(spec("RES1"), 1), finding("WOR1", 8),
                        finding("IMP1", 2), finding("SAME", 4), CheckOutcome.error(spec("BRK1"), 1, "boom"),
                        CheckOutcome.pass(spec("REC1"), 1), CheckOutcome.pass(spec("QUIET"), 1),
                        finding("EXTRA", 1)));

        assertThat(only(d, RunDiff.Change.NEW).currentCount()).isEqualTo(5);
        assertThat(only(d, RunDiff.Change.RESOLVED).previousCount()).isEqualTo(7);
        RunDiff.Entry worse = only(d, RunDiff.Change.WORSENED);
        assertThat(worse.delta()).isEqualTo(5);
        assertThat(only(d, RunDiff.Change.IMPROVED).delta()).isEqualTo(-7);
        assertThat(only(d, RunDiff.Change.UNCHANGED).checkId()).isEqualTo("SAME");
        assertThat(only(d, RunDiff.Change.BROKE).checkId()).isEqualTo("BRK1");
        assertThat(only(d, RunDiff.Change.RECOVERED).checkId()).isEqualTo("REC1");
        // passed both times is silence, not an entry
        assertThat(d.entries()).noneMatch(e -> e.checkId().equals("QUIET"));
        // present in only one run: flagged, never counted as resolved
        assertThat(d.of(RunDiff.Change.NOT_COMPARED)).extracting(RunDiff.Entry::checkId)
                .containsExactlyInAnyOrder("EXTRA", "GONE");
        assertThat(d.selectionsDiffer()).isTrue();
        assertThat(d.hasRegressions()).isTrue();
    }

    @Test
    void regressionsAreOnlyNewWorsenedOrBroke() {
        assertThat(RunDiff.between(previous("A", 1, 5), report(finding("A", 5))).hasRegressions()).isFalse();
        assertThat(RunDiff.between(previous("A", 1, 5), report(finding("A", 4))).hasRegressions()).isFalse();
        assertThat(RunDiff.between(previous("A", 1, 5), report(CheckOutcome.pass(spec("A"), 1))).hasRegressions()).isFalse();
        assertThat(RunDiff.between(previous("A", 1, 5), report(finding("A", 6))).hasRegressions()).isTrue();
        assertThat(RunDiff.between(previous("A", 0, 0), report(finding("A", 1))).hasRegressions()).isTrue();
        assertThat(RunDiff.between(previous("A", 0, 0), report(CheckOutcome.timeout(spec("A"), 1, 5))).hasRegressions()).isTrue();
    }

    @Test
    void orderIsWorstFirstThenById() {
        RunDiff d = RunDiff.between(previous("Z", 1, 1, "A", 0, 0, "M", 1, 9),
                report(finding("Z", 1), finding("A", 2), finding("M", 1)));
        assertThat(d.entries()).extracting(RunDiff.Entry::change)
                .containsExactly(RunDiff.Change.NEW, RunDiff.Change.UNCHANGED, RunDiff.Change.IMPROVED);
    }

    @Test
    void readsAJsonReportAndRejectsOtherFiles(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("run.json");
        Files.writeString(ok, """
                {"tool":"production-triage-toolkit","startedAt":"2026-09-18T09:00:00Z",
                 "target":{"host":"db","port":5432,"database":"bookings"},
                 "findings":[{"checkId":"DI002","matchCount":3}],
                 "passed":[{"checkId":"DI001"}],
                 "couldNotRun":[{"checkId":"DBH001"}]}
                """);
        Map<String, Object> prev = RunDiff.readPrevious(ok);
        assertThat(prev.get("startedAt")).isEqualTo("2026-09-18T09:00:00Z");
        assertThat(prev.get("target")).isEqualTo("db:5432/bookings");
        @SuppressWarnings("unchecked") Map<String, int[]> checks = (Map<String, int[]>) prev.get("checks");
        assertThat(checks.get("DI002")).containsExactly(1, 3);
        assertThat(checks.get("DI001")).containsExactly(0, 0);
        assertThat(checks.get("DBH001")).containsExactly(2, -1);

        Path bad = dir.resolve("other.json");
        Files.writeString(bad, "{\"hello\":1}");
        assertThatThrownBy(() -> RunDiff.readPrevious(bad)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a triage JSON report");
    }
}
