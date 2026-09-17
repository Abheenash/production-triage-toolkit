package com.abheenash.triage.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of a full run: every outcome, ranked, plus the exit code that follows from them.
 *
 * <p>Ranking is severity first, then match count descending, then check id. The tie-breaks matter:
 * two CRITICAL findings are not equally urgent if one matched 4 rows and the other 40,000, and a
 * stable final tie-break on id keeps output diffable between runs.
 */
public record RunReport(
        TargetInfo target,
        Instant startedAt,
        Duration duration,
        List<CheckOutcome> outcomes,
        Thresholds thresholds,
        Severity failOn) {

    private static final Comparator<CheckOutcome> RANKING =
            Comparator.comparing(CheckOutcome::severity)
                    .thenComparing(Comparator.comparingInt(CheckOutcome::matchCount).reversed())
                    .thenComparing(o -> o.spec().id());

    /** Findings only, most urgent first. */
    public List<CheckOutcome> findings() {
        return outcomes.stream().filter(CheckOutcome::isFinding).sorted(RANKING).toList();
    }

    public List<CheckOutcome> passed() {
        return outcomes.stream()
                .filter(o -> o.status() == CheckOutcome.Status.PASS)
                .sorted(Comparator.comparing(o -> o.spec().id()))
                .toList();
    }

    /** Checks that could not produce an answer. These are why exit code 2 exists. */
    public List<CheckOutcome> failures() {
        return outcomes.stream()
                .filter(CheckOutcome::didNotRun)
                .sorted(Comparator.comparing(o -> o.spec().id()))
                .toList();
    }

    public Map<Severity, Integer> countsBySeverity() {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (CheckOutcome o : findings()) {
            counts.merge(o.severity(), 1, Integer::sum);
        }
        return counts;
    }

    /** Total matching rows across all findings, not the number of findings. */
    public long totalMatchedRows() {
        return findings().stream().mapToLong(CheckOutcome::matchCount).sum();
    }

    /**
     * 0 healthy, 1 findings, 2 error.
     *
     * <p>A check that failed to run outranks any finding: if one diagnostic is broken, the honest
     * report is "this run is not trustworthy", not "one problem found".
     */
    public int exitCode() {
        if (!failures().isEmpty()) {
            return 2;
        }
        boolean actionable = findings().stream().anyMatch(o -> o.severity().atLeast(failOn));
        return actionable ? 1 : 0;
    }
}
