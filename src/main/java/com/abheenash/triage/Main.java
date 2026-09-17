package com.abheenash.triage;

import com.abheenash.triage.cli.TriageCommand;

/**
 * Entry point.
 *
 * <p>Nothing but the exit code lives here. Keeping {@code System.exit} out of the command class is
 * what lets the integration tests run the real CLI end to end, exit code and all, inside the test
 * JVM instead of shelling out.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(TriageCommand.execute(args));
    }
}
