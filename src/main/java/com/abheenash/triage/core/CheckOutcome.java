package com.abheenash.triage.core;

import java.util.List;
import java.util.Map;

/**
 * What happened when one check ran.
 *
 * <p>A check that could not run is kept distinct from a check that ran and found nothing. Folding
 * the two together is the classic monitoring failure: a broken check reports "all clear" forever.
 * {@link Status#TIMEOUT} and {@link Status#ERROR} both drive exit code 2, never 0.
 *
 * @param matchCount total matching rows, which is NOT {@code sampleRows.size()} -- the samples are
 *                   capped by {@code --sample-rows} while the count is exact
 */
public record CheckOutcome(
        CheckSpec spec,
        Status status,
        Severity severity,
        int matchCount,
        List<String> columns,
        List<Map<String, Object>> sampleRows,
        long durationMs,
        String errorMessage) {

    /**
     * A record is only shallowly immutable: without this, a caller keeps a live
     * reference to the very list it passed in and can mutate an outcome after it
     * has been reported. {@code List.copyOf} makes the guarantee real.
     */
    public CheckOutcome {
        columns = columns == null ? List.of() : List.copyOf(columns);
        sampleRows = sampleRows == null ? List.of() : List.copyOf(sampleRows);
    }

    public enum Status {
        /** Ran, matched nothing. */
        PASS,
        /** Ran, matched at least one row. */
        FINDING,
        /** Cancelled by the statement timeout. */
        TIMEOUT,
        /** Failed for any other reason: bad SQL, missing table, lost connection. */
        ERROR
    }

    public static CheckOutcome pass(CheckSpec spec, long durationMs) {
        return new CheckOutcome(spec, Status.PASS, Severity.INFO, 0, List.of(), List.of(), durationMs, null);
    }

    public static CheckOutcome finding(CheckSpec spec, int matchCount, List<String> columns,
                                       List<Map<String, Object>> sampleRows, long durationMs) {
        return new CheckOutcome(spec, Status.FINDING, spec.severityFor(matchCount), matchCount,
                List.copyOf(columns), List.copyOf(sampleRows), durationMs, null);
    }

    public static CheckOutcome timeout(CheckSpec spec, long durationMs, long timeoutMs) {
        return new CheckOutcome(spec, Status.TIMEOUT, Severity.HIGH, 0, List.of(), List.of(), durationMs,
                "cancelled by statement_timeout after " + timeoutMs + "ms");
    }

    public static CheckOutcome error(CheckSpec spec, long durationMs, String message) {
        return new CheckOutcome(spec, Status.ERROR, Severity.HIGH, 0, List.of(), List.of(), durationMs, message);
    }

    public boolean isFinding() {
        return status == Status.FINDING;
    }

    public boolean didNotRun() {
        return status == Status.TIMEOUT || status == Status.ERROR;
    }
}
