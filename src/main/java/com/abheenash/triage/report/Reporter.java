package com.abheenash.triage.report;

import com.abheenash.triage.core.RunReport;

import java.io.PrintStream;

/** Renders a finished run. Implementations must not change the report they are given. */
public interface Reporter {
    void write(RunReport report, PrintStream out);
}
