package com.abheenash.triage.cli;

import com.abheenash.triage.core.CheckCatalog;
import com.abheenash.triage.core.CheckGroup;
import com.abheenash.triage.core.CheckSpec;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.TargetInfo;
import com.abheenash.triage.core.Thresholds;
import com.abheenash.triage.core.TriageRunner;
import com.abheenash.triage.db.ConnectionFactory;
import com.abheenash.triage.db.ConnectionSettings;
import com.abheenash.triage.report.JsonReporter;
import com.abheenash.triage.report.PromptReporter;
import com.abheenash.triage.report.Reporter;
import com.abheenash.triage.report.TextReporter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "triage",
        mixinStandardHelpOptions = true,
        version = "production-triage-toolkit 1.0.0",
        sortOptions = false,
        usageHelpWidth = 100,
        description = {
                "Runs read-only SQL diagnostics against a PostgreSQL database, ranks what it finds",
                "by severity, and points at the runbook for each finding.",
                "",
                "Exit codes: 0 healthy, 1 findings, 2 the run was incomplete."},
        footer = {
                "",
                "The password is read from the environment (PGPASSWORD by default) and never from an",
                "option, because a command-line password is visible to every process on the host.",
                "",
                "Examples:",
                "  triage --host db.internal --database bookings --user readonly",
                "  triage --group dbhealth --format json | jq '.findings[].checkId'",
                "  triage --format prompt | pbcopy        # grounded context for an assistant",
                "  triage --check DI002 --check DI003 --sample-rows 20",
                "  triage --fail-on HIGH          # exit 1 only for HIGH and CRITICAL"})
public final class TriageCommand implements Callable<Integer> {

    @Option(names = {"-H", "--host"}, defaultValue = "${env:PGHOST:-localhost}",
            description = "Database host. Default: ${DEFAULT-VALUE}")
    String host;

    @Option(names = {"-p", "--port"}, defaultValue = "${env:PGPORT:-5432}",
            description = "Database port. Default: ${DEFAULT-VALUE}")
    int port;

    @Option(names = {"-d", "--database"}, defaultValue = "${env:PGDATABASE:-postgres}",
            description = "Database name. Default: ${DEFAULT-VALUE}")
    String database;

    @Option(names = {"-U", "--user"}, defaultValue = "${env:PGUSER:-postgres}",
            description = "Database user. Use a read-only role. Default: ${DEFAULT-VALUE}")
    String user;

    @Option(names = "--password-env", defaultValue = "PGPASSWORD",
            description = "Environment variable holding the password. Default: ${DEFAULT-VALUE}")
    String passwordEnv;

    @Option(names = "--check", paramLabel = "ID", arity = "1",
            description = "Run only this check, repeatable. See --list-checks.")
    List<String> checkIds = new ArrayList<>();

    @Option(names = "--group", paramLabel = "GROUP",
            description = "Run only this group: data, ops, dbhealth. Repeatable.")
    List<String> groupNames = new ArrayList<>();

    @Option(names = "--format", defaultValue = "text",
            description = "Output format: text, json, or prompt. 'prompt' emits the findings plus "
                    + "the full runbook for each as grounded context for an assistant. "
                    + "Default: ${DEFAULT-VALUE}")
    String format;

    @Option(names = "--timeout-ms", defaultValue = "5000",
            description = "Per-check statement timeout in milliseconds. Default: ${DEFAULT-VALUE}")
    long timeoutMs;

    @Option(names = "--connect-timeout", defaultValue = "10",
            description = "Connection timeout in seconds. Default: ${DEFAULT-VALUE}")
    int connectTimeoutSeconds;

    @Option(names = "--sample-rows", defaultValue = "5",
            description = "Sample rows to show per finding. Default: ${DEFAULT-VALUE}")
    int sampleRows;

    @Option(names = "--fail-on", defaultValue = "INFO", paramLabel = "SEVERITY",
            description = "Lowest severity that causes exit 1: CRITICAL, HIGH, MEDIUM, LOW, INFO. "
                    + "Default: ${DEFAULT-VALUE} (any finding).")
    String failOn;

    @Option(names = "--threshold", paramLabel = "KEY=VALUE",
            description = "Override a check threshold, repeatable. See --list-thresholds.")
    List<String> thresholdOverrides = new ArrayList<>();

    @Option(names = "--list-checks", description = "Print the check catalogue and exit.")
    boolean listChecks;

    @Option(names = "--list-thresholds", description = "Print tunable thresholds and their defaults, then exit.")
    boolean listThresholds;

    @Option(names = "--show-sql", paramLabel = "ID",
            description = "Print the exact SQL a check will run, then exit. Read it before you trust it.")
    String showSql;

    @Option(names = "--no-color", description = "Never colourise output.")
    boolean noColor;

    @Option(names = {"-v", "--verbose"}, description = "List every passing check, not just a summary.")
    boolean verbose;

    @Override
    public Integer call() {
        try {
            // Every selector is validated BEFORE the modes that exit early, so a mistyped group or
            // check id is always reported. Validating it later meant `--group databse --list-checks`
            // printed the whole catalogue and exited 0, leaving the user believing a filter had been
            // applied when their typo had been silently discarded.
            Thresholds thresholds = Thresholds.defaults().with(thresholdOverrides);
            List<CheckGroup> groups = groupNames.stream().map(CheckGroup::parse).toList();
            List<CheckSpec> selected = CheckCatalog.select(checkIds, groups);

            if (listThresholds) {
                printThresholds(thresholds);
                return ExitCode.HEALTHY;
            }
            if (listChecks) {
                printCatalogue(groups);
                return ExitCode.HEALTHY;
            }
            if (showSql != null) {
                CheckSpec spec = CheckCatalog.byId(showSql).orElseThrow(() -> new IllegalArgumentException(
                        "unknown check '" + showSql + "'; run --list-checks to see the 15 available"));
                System.out.println(CheckCatalog.loadSql(spec, thresholds));
                return ExitCode.HEALTHY;
            }

            Severity failOnSeverity = Severity.parse(failOn);
            validateNumericOptions();

            ConnectionSettings settings = new ConnectionSettings(
                    host, port, database, user, passwordEnv,
                    connectTimeoutSeconds,
                    // Give the socket room beyond the statement timeout, so a server-side
                    // cancellation is reported as a clean TIMEOUT rather than a dropped socket.
                    (int) Math.max(connectTimeoutSeconds, (timeoutMs / 1000) + 30),
                    "production-triage-toolkit/1.0.0");

            ConnectionFactory factory = new ConnectionFactory(settings);
            try (Connection conn = factory.open()) {
                TargetInfo target = factory.describe(conn);
                RunReport report = new TriageRunner(conn, thresholds, timeoutMs, sampleRows)
                        .run(selected, target, failOnSeverity);
                reporter().write(report, System.out);
                return report.exitCode();
            }

        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("triage: " + e.getMessage());
            return ExitCode.ERROR;
        } catch (java.sql.SQLException e) {
            System.err.println("triage: could not complete against " + user + "@" + host + ":" + port
                    + "/" + database + ": " + e.getMessage());
            return ExitCode.ERROR;
        } catch (Exception e) {
            System.err.println("triage: unexpected failure: " + e);
            return ExitCode.ERROR;
        }
    }

    private void validateNumericOptions() {
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("--timeout-ms must be at least 1");
        }
        if (sampleRows < 0) {
            throw new IllegalArgumentException("--sample-rows must not be negative");
        }
        if (connectTimeoutSeconds < 1) {
            throw new IllegalArgumentException("--connect-timeout must be at least 1");
        }
    }

    private Reporter reporter() {
        return switch (format.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "text" -> new TextReporter(useColour(), verbose);
            case "json" -> new JsonReporter(true);
            case "prompt" -> new PromptReporter();
            default -> throw new IllegalArgumentException(
                    "unknown --format '" + format + "'; expected text, json or prompt");
        };
    }

    /** Colour only for an interactive terminal, so redirected output stays clean. */
    private boolean useColour() {
        return !noColor && System.console() != null;
    }

    /** Lists the catalogue, narrowed to {@code groups} when the caller asked for some. */
    private void printCatalogue(List<CheckGroup> groups) {
        List<CheckGroup> shown = groups.isEmpty() ? List.of(CheckGroup.values()) : groups;
        long total = CheckCatalog.all().stream().filter(c -> shown.contains(c.group())).count();

        System.out.println();
        System.out.println("Production Triage Toolkit -- " + total + " checks"
                + (groups.isEmpty() ? "" : " in "
                    + groups.stream().map(CheckGroup::cliName).collect(java.util.stream.Collectors.joining(", "))));
        for (CheckGroup group : shown) {
            List<CheckSpec> inGroup = CheckCatalog.byGroup(group);
            System.out.println();
            System.out.printf("%s (--group %s), %d checks%n",
                    group.displayName(), group.cliName(), inGroup.size());
            for (CheckSpec spec : inGroup) {
                System.out.printf("  %-7s %-9s %s%n", spec.id(), spec.baseSeverity(), spec.title());
                System.out.printf("  %-7s %-9s impact:  %s%n", "", "", spec.userImpact());
                System.out.printf("  %-7s %-9s runbook: %s%n", "", "", spec.runbook());
                if (spec.escalateAtRows() > 0) {
                    System.out.printf("  %-7s %-9s escalates to %s at %d rows%n", "", "",
                            spec.baseSeverity().escalate(), spec.escalateAtRows());
                }
            }
        }
        System.out.println();
    }

    private void printThresholds(Thresholds thresholds) {
        System.out.println();
        System.out.println("Tunable thresholds (--threshold key=value)");
        System.out.println();
        thresholds.asMap().forEach((k, v) -> {
            String dflt = Thresholds.DEFAULTS.get(k);
            String note = v.equals(dflt) ? "" : "   (default " + dflt + ")";
            System.out.printf("  %-30s %s%s%n", k, v, note);
        });
        System.out.println();
    }

    public static int execute(String[] args) {
        return new CommandLine(new TriageCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("triage: " + ex.getMessage());
                    return ExitCode.ERROR;
                })
                .setParameterExceptionHandler((ex, argv) -> {
                    CommandLine cmd = ex.getCommandLine();
                    cmd.getErr().println("triage: " + ex.getMessage());
                    cmd.usage(cmd.getErr());
                    return ExitCode.ERROR;
                })
                .execute(args);
    }
}
