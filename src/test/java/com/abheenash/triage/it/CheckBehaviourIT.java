package com.abheenash.triage.it;

import com.abheenash.triage.core.CheckCatalog;
import com.abheenash.triage.core.CheckOutcome;
import com.abheenash.triage.core.RunReport;
import com.abheenash.triage.core.Severity;
import com.abheenash.triage.core.TargetInfo;
import com.abheenash.triage.core.Thresholds;
import com.abheenash.triage.core.TriageRunner;
import com.abheenash.triage.db.ConnectionFactory;
import com.abheenash.triage.db.TestConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's two headline success criteria, executed against a real PostgreSQL:
 * clean data produces nothing, and the six scenarios produce exactly their six checks.
 */
class CheckBehaviourIT extends AbstractDatabaseIT {

    @BeforeEach
    void freshCleanDatabase() throws Exception {
        seedClean();
    }

    private RunReport run() throws Exception {
        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            TargetInfo target = factory.describe(conn);
            return new TriageRunner(conn, Thresholds.defaults(), 10_000, 5)
                    .run(CheckCatalog.all(), target, Severity.INFO);
        }
    }

    @Test
    void allFifteenChecksPassOnCleanDataAndTheRunExitsZero() throws Exception {
        RunReport report = run();

        assertThat(report.failures())
                .as("no check should fail to run: %s", describeFailures(report))
                .isEmpty();
        assertThat(report.findings())
                .as("clean data must produce no findings: %s", describeFindings(report))
                .isEmpty();
        assertThat(report.passed()).hasSize(15);
        assertThat(report.exitCode()).isZero();
    }

    @Test
    void theSixScenariosTripExactlyTheirSixChecksAndTheRunExitsOne() throws Exception {
        for (String n : List.of("01", "02", "03", "04", "05", "06")) {
            injectScenario(n);
        }

        RunReport report = run();

        assertThat(report.failures()).isEmpty();
        assertThat(report.findings().stream().map(o -> o.spec().id()))
                .containsExactlyInAnyOrder("DI001", "DI002", "DI003", "DI004", "OPS001", "OPS003");
        assertThat(report.exitCode()).isEqualTo(1);

        // Each scenario injects exactly three problems. An exact count, not "at least one",
        // because an over-broad check that also matches clean rows would still pass a loose
        // assertion while being wrong.
        assertThat(report.findings()).allSatisfy(o ->
                assertThat(o.matchCount()).as("check %s", o.spec().id()).isEqualTo(3));
    }

    @ParameterizedTest(name = "scenario {0} trips only {1}")
    @CsvSource({
            "01, DI001",
            "02, DI002",
            "03, DI003",
            "04, DI004",
            "05, OPS001",
            "06, OPS003"})
    void eachScenarioIsIndependentOfTheOthers(String scenario, String expectedCheck) throws Exception {
        injectScenario(scenario);

        RunReport report = run();

        assertThat(report.findings().stream().map(o -> o.spec().id()))
                .as("scenario %s should trip %s and nothing else", scenario, expectedCheck)
                .containsExactly(expectedCheck);
        assertThat(report.findings().get(0).matchCount()).isEqualTo(3);
    }

    @Test
    void findingsCarrySampleRowsAndAnExactCountThatIsNotTheSampleSize() throws Exception {
        injectScenario("01");

        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            // Sample capped at 1, but the count must still be the true 3.
            RunReport report = new TriageRunner(conn, Thresholds.defaults(), 10_000, 1)
                    .run(List.of(CheckCatalog.byId("DI001").orElseThrow()),
                            factory.describe(conn), Severity.INFO);

            CheckOutcome o = report.findings().get(0);
            assertThat(o.matchCount()).isEqualTo(3);
            assertThat(o.sampleRows()).hasSize(1);
            assertThat(o.columns()).contains("booking_id", "missing_room_id");
            assertThat(o.sampleRows().get(0)).containsKey("missing_room_id");
        }
    }

    @Test
    void raisingAThresholdChangesWhatCountsAsAProblem() throws Exception {
        // Scenario 05 leaves three runs going for 95 minutes, 3 hours and 4 hours. Under the
        // shipped 30-minute limit all three are stuck. Raise the limit past the longest of them
        // and the same data is no longer a problem -- which is the whole point of the knob.
        injectScenario("05");

        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            TargetInfo target = factory.describe(conn);
            var ops001 = List.of(CheckCatalog.byId("OPS001").orElseThrow());

            RunReport shipped = new TriageRunner(conn, Thresholds.defaults(), 10_000, 5)
                    .run(ops001, target, Severity.INFO);
            assertThat(shipped.findings()).hasSize(1);
            assertThat(shipped.findings().get(0).matchCount()).isEqualTo(3);

            Thresholds relaxed = Thresholds.defaults().with(List.of("stuck_job_minutes=600"));
            RunReport lenient = new TriageRunner(conn, relaxed, 10_000, 5)
                    .run(ops001, target, Severity.INFO);
            assertThat(lenient.findings()).isEmpty();
        }
    }

    private static String describeFindings(RunReport r) {
        return r.findings().stream()
                .map(o -> o.spec().id() + "=" + o.matchCount() + " " + sample(o))
                .toList().toString();
    }

    private static String sample(CheckOutcome o) {
        return o.sampleRows().isEmpty() ? "" : o.sampleRows().get(0).toString();
    }

    private static String describeFailures(RunReport r) {
        return r.failures().stream()
                .map(o -> o.spec().id() + ": " + o.errorMessage())
                .toList().toString();
    }

    @Test
    void sampleRowValuesSurviveAsJsonFriendlyTypes() throws Exception {
        injectScenario("03");
        ConnectionFactory factory = TestConnectionFactory.withPassword(settings(), PASSWORD);
        try (Connection conn = factory.open()) {
            RunReport report = new TriageRunner(conn, Thresholds.defaults(), 10_000, 5)
                    .run(List.of(CheckCatalog.byId("DI003").orElseThrow()),
                            factory.describe(conn), Severity.INFO);

            Map<String, Object> row = report.findings().get(0).sampleRows().get(0);
            assertThat(row.get("terminated_at")).isInstanceOf(String.class);
            assertThat((String) row.get("terminated_at")).endsWith("Z");
            assertThat(row.get("badge_number")).isInstanceOf(String.class);
            assertThat(row.get("employee_id")).isInstanceOf(Number.class);
        }
    }
}
