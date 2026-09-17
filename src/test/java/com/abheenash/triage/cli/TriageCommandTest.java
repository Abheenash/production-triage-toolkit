package com.abheenash.triage.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the CLI that never touch a database.
 *
 * <p>Argument validation, the catalogue listing, and {@code --show-sql} all answer without
 * connecting, so they can be tested in-process and fast. CliIT covers the same surface through a
 * real subprocess for the sake of genuine exit codes, but that JVM is not instrumented and those
 * tests cost a second each; these run in milliseconds and assert the messages precisely.
 *
 * <p>Worth testing on its own merits: every one of these paths is something a person hits while
 * mistyping a command at 2am, and an unhelpful message there is a real cost.
 */
class TriageCommandTest {

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void listChecksDescribesAllFifteenWithoutConnecting() {
        int code = TriageCommand.execute(new String[]{"--list-checks"});

        assertThat(code).isEqualTo(ExitCode.HEALTHY);
        String text = stdout();
        assertThat(text).contains("15 checks");
        for (String id : new String[]{"DI001", "DI002", "DI003", "DI004", "DI005", "DI006", "DI007",
                "OPS001", "OPS002", "OPS003", "DBH001", "DBH002", "DBH003", "DBH004", "DBH005"}) {
            assertThat(text).as("catalogue should list %s", id).contains(id);
        }
        assertThat(text)
                .contains("Data integrity (--group data), 7 checks")
                .contains("Operations (--group ops), 3 checks")
                .contains("Database health (--group dbhealth), 5 checks")
                .contains("impact:")
                .contains("runbook:")
                .contains("escalates to CRITICAL at 25 rows");
    }

    @Test
    void listThresholdsShowsEveryKnobAndMarksOverrides() {
        assertThat(TriageCommand.execute(new String[]{"--list-thresholds"})).isEqualTo(ExitCode.HEALTHY);
        assertThat(stdout())
                .contains("booking_window_days")
                .contains("stuck_job_minutes")
                .contains("idle_in_transaction_seconds");

        captureStreams();
        TriageCommand.execute(new String[]{"--list-thresholds", "--threshold", "booking_window_days=30"});
        assertThat(stdout()).contains("30").contains("(default 7)");
    }

    @Test
    void showSqlPrintsTheRealQueryWithThresholdsApplied() {
        assertThat(TriageCommand.execute(new String[]{"--show-sql", "DI002"})).isEqualTo(ExitCode.HEALTHY);

        String sql = stdout();
        assertThat(sql)
                .contains("interval '7 days'")
                .doesNotContain("${")
                .containsIgnoringCase("select");
    }

    @Test
    void showSqlHonoursAThresholdOverride() {
        TriageCommand.execute(new String[]{"--show-sql", "DI002", "--threshold", "booking_window_days=45"});
        assertThat(stdout()).contains("interval '45 days'").doesNotContain("interval '7 days'");
    }

    @Test
    void showSqlIsCaseInsensitiveOnTheCheckId() {
        assertThat(TriageCommand.execute(new String[]{"--show-sql", "di002"})).isEqualTo(ExitCode.HEALTHY);
        assertThat(stdout()).isNotBlank();
    }

    @Test
    void anUnknownCheckExitsTwoAndPointsAtListChecks() {
        assertThat(TriageCommand.execute(new String[]{"--show-sql", "DI999"})).isEqualTo(ExitCode.ERROR);
        assertThat(stderr()).contains("DI999").contains("--list-checks");
    }

    @Test
    void anUnknownThresholdExitsTwoAndNamesTheRealOnes() {
        assertThat(TriageCommand.execute(new String[]{"--list-thresholds", "--threshold", "nope=1"}))
                .isEqualTo(ExitCode.ERROR);
        assertThat(stderr()).contains("unknown threshold").contains("booking_window_days");
    }

    @Test
    void aThresholdCarryingSqlIsRejectedAsNotANumber() {
        // The substitution-safety boundary, asserted at the CLI surface a user actually types at.
        assertThat(TriageCommand.execute(new String[]{
                "--list-thresholds", "--threshold", "booking_window_days=7; DROP TABLE bookings"}))
                .isEqualTo(ExitCode.ERROR);
        assertThat(stderr()).contains("must be a number");
    }

    @Test
    void anUnknownGroupExitsTwoAndNamesTheValidGroups() {
        assertThat(TriageCommand.execute(new String[]{"--group", "database", "--list-checks"}))
                .isEqualTo(ExitCode.ERROR);
        assertThat(stderr()).contains("unknown group").contains("dbhealth");
    }

    @Test
    void listChecksHonoursAGroupFilterInsteadOfIgnoringIt() {
        assertThat(TriageCommand.execute(new String[]{"--group", "ops", "--list-checks"}))
                .isEqualTo(ExitCode.HEALTHY);
        assertThat(stdout())
                .contains("3 checks in ops")
                .contains("OPS001")
                .doesNotContain("DI001")
                .doesNotContain("DBH001");
    }

    @Test
    void anUnparseableOptionExitsTwoRatherThanThrowing() {
        assertThat(TriageCommand.execute(new String[]{"--port", "not-a-number"})).isEqualTo(ExitCode.ERROR);
        assertThat(stderr()).contains("triage:");
    }

    @Test
    void helpAndVersionSucceedWithoutADatabase() {
        assertThat(TriageCommand.execute(new String[]{"--help"})).isEqualTo(ExitCode.HEALTHY);
        assertThat(stdout()).contains("--check").contains("--fail-on").contains("Exit codes");

        captureStreams();
        assertThat(TriageCommand.execute(new String[]{"--version"})).isEqualTo(ExitCode.HEALTHY);
        assertThat(stdout()).contains("1.0.0");
    }

    @Test
    void helpExplainsWhyThereIsNoPasswordOption() {
        // If the reason ever disappears from the help, somebody will "helpfully" add --password.
        TriageCommand.execute(new String[]{"--help"});
        assertThat(stdout())
                .contains("PGPASSWORD")
                .contains("visible to every process");
    }

    @Test
    void exitCodesAreTheDocumentedThree() {
        assertThat(ExitCode.HEALTHY).isZero();
        assertThat(ExitCode.FINDINGS).isEqualTo(1);
        assertThat(ExitCode.ERROR).isEqualTo(2);
    }
}
