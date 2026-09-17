package com.abheenash.triage.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The CLI contract, exercised as a real process.
 *
 * <p>Run as a subprocess rather than by calling into the command class, because the things being
 * tested here -- the exit code, and that the password comes from the environment and nowhere else
 * -- only exist at the process boundary. Calling {@code TriageCommand} in-process would test
 * neither.
 */
class CliIT extends AbstractDatabaseIT {

    private static final Path JAR = Path.of("target", "triage.jar");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void cleanDatabaseAndBuiltJar() throws Exception {
        assumeThat(Files.exists(JAR))
                .as("target/triage.jar must be built; run 'mvn verify', not 'mvn test'")
                .isTrue();
        seedClean();
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }

    private Result runCli(Map<String, String> extraEnv, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", JAR.toString()));
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().remove("PGPASSWORD");
        pb.environment().putAll(extraEnv);

        Process process = pb.start();
        String out = drain(process.getInputStream());
        String err = drain(process.getErrorStream());
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("CLI should not hang").isTrue();
        return new Result(process.exitValue(), out, err);
    }

    private Result runAgainstDatabase(String... extraArgs) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "--host", HOST,
                "--port", String.valueOf(PORT),
                "--database", DB,
                "--user", USER,
                "--no-color"));
        args.addAll(List.of(extraArgs));
        return runCli(Map.of("PGPASSWORD", PASSWORD), args.toArray(String[]::new));
    }

    private static String drain(InputStream in) throws Exception {
        try (in; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void cleanDatabaseExitsZero() throws Exception {
        Result r = runAgainstDatabase();
        assertThat(r.exitCode()).as("stdout:%n%s%nstderr:%n%s", r.stdout(), r.stderr()).isZero();
        assertThat(r.stdout()).contains("All 15 checks passed");
    }

    @Test
    void findingsExitOne() throws Exception {
        injectScenario("03");
        Result r = runAgainstDatabase();
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.stdout())
                .contains("CRITICAL")
                .contains("DI003")
                .contains("runbooks/DI003-ghost-badges.md");
    }

    @Test
    void failOnRaisesTheBarAndReturnsToZeroWithoutHidingTheFinding() throws Exception {
        injectScenario("04");
        Result withDefault = runAgainstDatabase();
        assertThat(withDefault.exitCode()).isEqualTo(1);

        Result strict = runAgainstDatabase("--fail-on", "HIGH");
        assertThat(strict.exitCode()).isZero();
        assertThat(strict.stdout()).contains("DI004");
    }

    @Test
    void anUnreachableDatabaseExitsTwo() throws Exception {
        Result r = runCli(Map.of("PGPASSWORD", PASSWORD),
                "--host", "127.0.0.1", "--port", "1", "--database", DB, "--user", USER,
                "--connect-timeout", "2", "--no-color");
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.stderr()).contains("triage:");
    }

    @Test
    void anAbsentPasswordExitsTwoAndExplainsWhyThereIsNoPasswordFlag() throws Exception {
        Result r = runCli(Map.of(),
                "--host", HOST,
                "--port", String.valueOf(PORT),
                "--database", DB, "--user", USER, "--no-color");

        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.stderr())
                .contains("PGPASSWORD")
                .contains("visible to every process");
    }

    @Test
    void anUnknownCheckExitsTwoRatherThanRunningNothing() throws Exception {
        Result r = runAgainstDatabase("--check", "DI999");
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(r.stderr()).contains("DI999").contains("--list-checks");
    }

    @Test
    void jsonOutputIsValidAndCarriesWhatAnAlertNeeds() throws Exception {
        injectScenario("05");
        Result r = runAgainstDatabase("--format", "json");

        assertThat(r.exitCode()).isEqualTo(1);
        JsonNode json = MAPPER.readTree(r.stdout());

        assertThat(json.get("tool").asText()).isEqualTo("production-triage-toolkit");
        assertThat(json.get("exitCode").asInt()).isEqualTo(1);
        assertThat(json.get("summary").get("checksRun").asInt()).isEqualTo(15);
        assertThat(json.get("summary").get("findings").asInt()).isEqualTo(1);
        assertThat(json.get("target").get("serverVersion").asText()).isNotBlank();

        JsonNode finding = json.get("findings").get(0);
        assertThat(finding.get("checkId").asText()).isEqualTo("OPS001");
        assertThat(finding.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(finding.get("matchCount").asInt()).isEqualTo(3);
        assertThat(finding.get("runbook").asText()).isEqualTo("runbooks/OPS001-stuck-sync-job.md");
        assertThat(finding.get("sampleRows")).hasSize(3);
    }

    @Test
    void groupSelectionRunsOnlyThatGroup() throws Exception {
        Result r = runAgainstDatabase("--group", "dbhealth", "--format", "json");
        JsonNode json = MAPPER.readTree(r.stdout());
        assertThat(json.get("summary").get("checksRun").asInt()).isEqualTo(5);
        assertThat(r.exitCode()).isZero();
    }

    @Test
    void listChecksDescribesAllFifteenWithoutTouchingTheDatabase() throws Exception {
        Result r = runCli(Map.of(), "--list-checks");
        assertThat(r.exitCode()).isZero();
        for (String id : List.of("DI001", "DI007", "OPS001", "OPS003", "DBH001", "DBH005")) {
            assertThat(r.stdout()).contains(id);
        }
        assertThat(r.stdout()).contains("runbooks/");
    }

    @Test
    void showSqlPrintsTheExactQueryWithThresholdsAlreadyApplied() throws Exception {
        Result r = runCli(Map.of(), "--show-sql", "DI002", "--threshold", "booking_window_days=45");
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout())
                .contains("interval '45 days'")
                .doesNotContain("${");
    }

    @Test
    void helpAndVersionWork() throws Exception {
        assertThat(runCli(Map.of(), "--help").exitCode()).isZero();
        Result version = runCli(Map.of(), "--version");
        assertThat(version.exitCode()).isZero();
        assertThat(version.stdout()).contains("1.0.0");
    }
}
