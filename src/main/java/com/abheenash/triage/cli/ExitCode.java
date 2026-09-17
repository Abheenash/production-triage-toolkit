package com.abheenash.triage.cli;

/**
 * The contract with whatever runs this tool: cron, a CI job, an EventBridge target.
 *
 * <p>Three codes, and the distinction between 1 and 2 is the important one. "I looked and found
 * problems" and "I could not look" must never be the same signal, or a broken deployment of the
 * toolkit reads as a healthy database.
 */
public final class ExitCode {
    /** Every selected check ran and found nothing at or above the fail-on severity. */
    public static final int HEALTHY = 0;
    /** Every selected check ran; at least one found something worth acting on. */
    public static final int FINDINGS = 1;
    /** Something prevented a complete answer: bad arguments, no connection, a check that failed. */
    public static final int ERROR = 2;

    private ExitCode() {
    }
}
