package com.abheenash.triage.report;

import com.abheenash.triage.core.RunDiff;
import com.abheenash.triage.core.RunReport;

import java.io.PrintStream;

/** Renders a finished run. Implementations must not change the report they are given. */
public interface Reporter {
    void write(RunReport report, PrintStream out);

    /**
     * Renders a run together with how it differs from a previous one ({@code --compare}).
     * Formats that have no way to show a comparison fall back to the plain report.
     */
    default void write(RunReport report, RunDiff diff, PrintStream out) {
        write(report, out);
    }
}
